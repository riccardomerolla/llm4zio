<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.text.NumberFormat" %>
<%@ page import="com.meridian.web.AccountServlet" %>
<%--
  accounts.jsp - Meridian Savings Bank HomeBank II
  Account list.  Populated by AccountServlet (GET /accounts); do not
  address this page directly, it expects the "accounts" attribute.
--%>
<html>
<head>
<title>Meridian Savings Bank - Your Accounts</title>
<style type="text/css">
  body { font-family: Verdana, Arial, sans-serif; font-size: 11px; }
  h2, h3 { color: #003366; }
  table.acct { border-collapse: collapse; }
  table.acct td, table.acct th { border: 1px solid #003366; padding: 4px 8px; }
  table.acct th { background-color: #003366; color: #ffffff; text-align: left; }
  .num { text-align: right; }
</style>
</head>
<body>
<h2>Meridian Savings Bank &#8212; HomeBank</h2>
<h3>Your accounts</h3>
<%
    List accounts = (List) request.getAttribute("accounts");
    NumberFormat money = NumberFormat.getNumberInstance(Locale.US);
    money.setMinimumFractionDigits(2);
    money.setMaximumFractionDigits(2);
    if (accounts == null || accounts.isEmpty()) {
%>
<p>No accounts on file. Please contact your branch.</p>
<%
    } else {
%>
<table class="acct">
  <tr>
    <th>Account</th>
    <th>Status</th>
    <th>Balance</th>
    <th>&nbsp;</th>
  </tr>
<%
        for (int i = 0; i < accounts.size(); i++) {
            AccountServlet.AccountRow acct =
                (AccountServlet.AccountRow) accounts.get(i);
            String statusText = "Unknown";
            if ("A".equals(acct.status)) {
                statusText = "Active";
            } else if ("F".equals(acct.status)) {
                statusText = "Frozen";
            } else if ("C".equals(acct.status)) {
                statusText = "Closed";
            } else if ("D".equals(acct.status)) {
                statusText = "Dormant";
            }
%>
  <tr>
    <td><%= acct.acctId %></td>
    <td><%= statusText %></td>
    <td class="num"><%= money.format(acct.balance) %></td>
    <td><a href="transfer">Transfer funds</a></td>
  </tr>
<%
        }
%>
</table>
<%
    }
%>
<p><small>Balances are as of last night's batch run.</small></p>
</body>
</html>
