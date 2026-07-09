package com.meridian.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;

/**
 * AccountServlet - Meridian Savings Bank internet banking (HomeBank II).
 *
 * GET /accounts: lists the logged-on customer's accounts straight from
 * the DB2 ACCOUNT master (subsystem DB2P) - the same table the nightly
 * batch (ACCTXFR, INTCALC) posts to.  Read-only: this application never
 * updates ACCOUNT.
 *
 * MAINT LOG
 *   2004-09  T.BRENNAN   ORIGINAL (HOMEBANK II PILOT, BRANCH 0100 ONLY)
 *   2005-03  T.BRENNAN   ALL BRANCHES; SQL MOVED TO INNER DAO
 */
public class AccountServlet extends HttpServlet {

    private static final String DS_NAME = "java:comp/env/jdbc/MeridianDB2";

    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("custId") == null) {
            // Not logged on, or the session timed out.  Bounce to the
            // logon page with a message.  The logon flow itself belongs
            // to the security team; login.jsp is a stub in this app.
            response.sendRedirect("login.jsp?reason=expired");
            return;
        }

        String custId = (String) session.getAttribute("custId");

        List accounts;
        try {
            accounts = new AccountDAO().findByCustomer(custId);
        } catch (NamingException ne) {
            throw new ServletException(
                "DataSource lookup failed: " + DS_NAME, ne);
        } catch (SQLException se) {
            throw new ServletException(
                "ACCOUNT query failed for customer " + custId, se);
        }

        request.setAttribute("accounts", accounts);
        request.getRequestDispatcher("/accounts.jsp")
               .forward(request, response);
    }

    /**
     * One row of the ACCOUNT master, as shown on the account list.
     * Status codes are the batch codes:
     * A=Active F=Frozen C=Closed D=Dormant.
     */
    public static class AccountRow {
        public String acctId;
        public String custId;
        public String status;
        public BigDecimal balance;
        public String odFlag;   // Y = overdraft protected
    }

    /**
     * Inline JDBC DAO.  Plain SQL against DB2 through the container
     * pool - no O/R mapper, per the 2004 architecture note.
     */
    static class AccountDAO {

        List findByCustomer(String custId)
                throws NamingException, SQLException {
            List rows = new ArrayList();
            Connection conn = null;
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                InitialContext ctx = new InitialContext();
                DataSource ds = (DataSource) ctx.lookup(DS_NAME);
                conn = ds.getConnection();
                stmt = conn.prepareStatement(
                    "SELECT ACCT_ID, CUST_ID, STATUS, BALANCE, OD_FLAG"
                    + " FROM ACCOUNT"
                    + " WHERE CUST_ID = ?"
                    + " ORDER BY ACCT_ID");
                stmt.setString(1, custId);
                rs = stmt.executeQuery();
                while (rs.next()) {
                    AccountRow row = new AccountRow();
                    row.acctId  = rs.getString("ACCT_ID");
                    row.custId  = rs.getString("CUST_ID");
                    row.status  = rs.getString("STATUS");
                    row.balance = rs.getBigDecimal("BALANCE");
                    row.odFlag  = rs.getString("OD_FLAG");
                    rows.add(row);
                }
                return rows;
            } finally {
                if (rs != null) {
                    try { rs.close(); } catch (SQLException ignore) { }
                }
                if (stmt != null) {
                    try { stmt.close(); } catch (SQLException ignore) { }
                }
                if (conn != null) {
                    try { conn.close(); } catch (SQLException ignore) { }
                }
            }
        }
    }
}
