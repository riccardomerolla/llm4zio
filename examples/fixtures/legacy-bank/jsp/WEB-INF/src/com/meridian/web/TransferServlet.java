package com.meridian.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;

/**
 * TransferServlet - Meridian Savings Bank internet banking (HomeBank II).
 *
 * GET  /transfer          shows the transfer form (transfer.jsp)
 * POST /transfer          validates; large transfers go to confirm.jsp,
 *                         the rest are enqueued immediately
 * POST /transfer/confirm  enqueues a previously confirmed large transfer
 *
 * IMPORTANT: the web NEVER posts a transfer.  The mainframe stays the
 * system of record.  We only INSERT a pending request into XFER_REQUEST
 * (CHANNEL='WEB', STATUS='P'); the nightly XFERDLY job extracts those
 * rows into the merged channel file and ACCTXFR does the real posting -
 * account status checks, the 5000.00 daily limit, fees and overdraft
 * handling all happen there, NOT here.  What we validate here is only
 * what the branch desk calls "form hygiene", plus the web-only
 * confirmation rule below.
 *
 * MAINT LOG
 *   2004-11  T.BRENNAN   ORIGINAL
 *   2005-06  L.FERRARO   CONFIRMATION STEP FOR LARGE TRANSFERS
 *                        PER OPS MEMO WEB-114
 */
public class TransferServlet extends HttpServlet {

    private static final String DS_NAME = "java:comp/env/jdbc/MeridianDB2";

    /**
     * Web channel rule (ops memo WEB-114, 2004): a single transfer over
     * 2500.00 must be re-confirmed on a separate screen before we accept
     * it.  This rule exists ONLY on the web - branch, ATM and telebank
     * requests go straight into the nightly file, and the batch itself
     * has never heard of it.
     */
    private static final BigDecimal CONFIRM_THRESHOLD =
        new BigDecimal("2500.00");

    /** XFER_REQUEST.MEMO is CHAR(25), same as the batch input record. */
    private static final int MEMO_MAX = 25;

    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("custId") == null) {
            response.sendRedirect("login.jsp?reason=expired");
            return;
        }
        if ("/transfer/confirm".equals(request.getServletPath())) {
            // Nothing to confirm on a GET - start over at the form.
            response.sendRedirect("transfer");
            return;
        }
        request.getRequestDispatcher("/transfer.jsp")
               .forward(request, response);
    }

    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("custId") == null) {
            // Session timed out mid-transfer: nothing was enqueued yet,
            // so a plain redirect with a message is safe.
            response.sendRedirect("login.jsp?reason=expired");
            return;
        }
        String custId = (String) session.getAttribute("custId");

        String fromAcct   = trimmed(request.getParameter("fromAcct"));
        String toAcct     = trimmed(request.getParameter("toAcct"));
        String amountText = trimmed(request.getParameter("amount"));
        String memo       = trimmed(request.getParameter("memo"));
        if (memo.length() > MEMO_MAX) {
            // Silently truncated, same as the branch terminals do.
            memo = memo.substring(0, MEMO_MAX);
        }

        // Amount must be a plain positive decimal with at most two
        // decimal places.  No thousands separators (branch standard).
        BigDecimal amount = parseAmount(amountText);
        if (amount == null) {
            backToForm(request, response, "Enter a valid amount");
            return;
        }
        if (fromAcct.length() == 0 || toAcct.length() == 0) {
            backToForm(request, response, "Enter both account numbers");
            return;
        }
        if (fromAcct.equals(toAcct)) {
            backToForm(request, response, "Source and target must differ");
            return;
        }

        boolean confirmed =
            "/transfer/confirm".equals(request.getServletPath());

        if (!confirmed && amount.compareTo(CONFIRM_THRESHOLD) > 0) {
            // Large transfer: show the confirmation screen.  The values
            // travel back as hidden fields and are re-validated above
            // when the confirmation POST arrives (hidden fields can be
            // tampered with).
            request.setAttribute("fromAcct", fromAcct);
            request.setAttribute("toAcct", toAcct);
            request.setAttribute("amount", amount.toString());
            request.setAttribute("memo", memo);
            request.getRequestDispatcher("/confirm.jsp")
                   .forward(request, response);
            return;
        }

        try {
            enqueue(custId, fromAcct, toAcct, amount, memo);
        } catch (NamingException ne) {
            throw new ServletException(
                "DataSource lookup failed: " + DS_NAME, ne);
        } catch (SQLException se) {
            throw new ServletException("XFER_REQUEST insert failed", se);
        }

        request.setAttribute("message",
            "Transfer request accepted. It will be posted overnight; "
            + "check your account list tomorrow.");
        request.getRequestDispatcher("/transfer.jsp")
               .forward(request, response);
    }

    /** Positive, at most 2 decimal places, plain digits - else null. */
    private BigDecimal parseAmount(String text) {
        if (text == null || text.length() == 0) {
            return null;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(text);
        } catch (NumberFormatException nfe) {
            return null;
        }
        if (amount.signum() <= 0) {
            return null;
        }
        if (amount.scale() > 2) {
            return null;
        }
        return amount;
    }

    private void backToForm(HttpServletRequest request,
                            HttpServletResponse response,
                            String error)
            throws ServletException, IOException {
        request.setAttribute("error", error);
        request.getRequestDispatcher("/transfer.jsp")
               .forward(request, response);
    }

    private String trimmed(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Queue the request for tonight's run.  STATUS 'P' = pending pickup;
     * the XFERDLY extract flips it to 'X' once written to the channel
     * file.  REQ_DATE is the DB2 current date, matching XFER-REQ-DATE
     * on the batch input record.
     */
    private void enqueue(String custId, String fromAcct, String toAcct,
                         BigDecimal amount, String memo)
            throws NamingException, SQLException {
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            InitialContext ctx = new InitialContext();
            DataSource ds = (DataSource) ctx.lookup(DS_NAME);
            conn = ds.getConnection();
            stmt = conn.prepareStatement(
                "INSERT INTO XFER_REQUEST"
                + " (SRC_ACCT, TGT_ACCT, AMOUNT, MEMO, CUST_ID,"
                + "  CHANNEL, STATUS, REQ_DATE)"
                + " VALUES (?, ?, ?, ?, ?, 'WEB', 'P', CURRENT DATE)");
            stmt.setString(1, fromAcct);
            stmt.setString(2, toAcct);
            stmt.setBigDecimal(3, amount);
            stmt.setString(4, memo);
            stmt.setString(5, custId);
            stmt.executeUpdate();
        } finally {
            if (stmt != null) {
                try { stmt.close(); } catch (SQLException ignore) { }
            }
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignore) { }
            }
        }
    }
}
