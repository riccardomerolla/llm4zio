<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" %>
<%--
  transfer.jsp - Meridian Savings Bank HomeBank II
  Transfer request form.  Posts to TransferServlet (/transfer).
--%>
<%
    String error   = (String) request.getAttribute("error");
    String message = (String) request.getAttribute("message");
    String fromAcct = request.getParameter("fromAcct");
    String toAcct   = request.getParameter("toAcct");
    String amount   = request.getParameter("amount");
    String memo     = request.getParameter("memo");
    if (fromAcct == null) fromAcct = "";
    if (toAcct == null)   toAcct = "";
    if (amount == null)   amount = "";
    if (memo == null)     memo = "";
%>
<html>
<head>
<title>Meridian Savings Bank - Transfer Funds</title>
<style type="text/css">
  body { font-family: Verdana, Arial, sans-serif; font-size: 11px; }
  h2, h3 { color: #003366; }
  .error { color: #cc0000; font-weight: bold; }
  .ok { color: #006600; font-weight: bold; }
  td { padding: 3px 6px; }
</style>
</head>
<body>
<h2>Meridian Savings Bank &#8212; HomeBank</h2>
<h3>Transfer funds</h3>
<% if (error != null) { %>
<p class="error"><%= error %></p>
<% } %>
<% if (message != null) { %>
<p class="ok"><%= message %></p>
<% } %>
<form method="post" action="transfer">
<table>
  <tr>
    <td>From account:</td>
    <td><input type="text" name="fromAcct" size="10" maxlength="10" value="<%= fromAcct %>"></td>
  </tr>
  <tr>
    <td>To account:</td>
    <td><input type="text" name="toAcct" size="10" maxlength="10" value="<%= toAcct %>"></td>
  </tr>
  <tr>
    <td>Amount:</td>
    <td><input type="text" name="amount" size="12" value="<%= amount %>"> (e.g. 125.00)</td>
  </tr>
  <tr>
    <td>Memo:</td>
    <td><input type="text" name="memo" size="25" maxlength="25" value="<%= memo %>"></td>
  </tr>
  <tr>
    <td>&nbsp;</td>
    <td><input type="submit" value="Continue"></td>
  </tr>
</table>
</form>
<p><small>Transfer requests received before 9:00 PM are posted in
tonight's run. Requests are subject to the bank's standard limits
and fees.</small></p>
<p><a href="accounts">Back to your accounts</a></p>
</body>
</html>
