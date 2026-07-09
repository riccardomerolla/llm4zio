<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" %>
<%--
  confirm.jsp - Meridian Savings Bank HomeBank II
  Confirmation screen for large transfers.  Reached only by a forward
  from TransferServlet (POST /transfer); re-posts to /transfer/confirm.
--%>
<%
    String fromAcct = (String) request.getAttribute("fromAcct");
    String toAcct   = (String) request.getAttribute("toAcct");
    String amount   = (String) request.getAttribute("amount");
    String memo     = (String) request.getAttribute("memo");
    if (fromAcct == null) fromAcct = "";
    if (toAcct == null)   toAcct = "";
    if (amount == null)   amount = "";
    if (memo == null)     memo = "";
%>
<html>
<head>
<title>Meridian Savings Bank - Confirm Transfer</title>
<style type="text/css">
  body { font-family: Verdana, Arial, sans-serif; font-size: 11px; }
  h2, h3 { color: #003366; }
  td { padding: 3px 6px; }
  td.lbl { font-weight: bold; }
</style>
</head>
<body>
<h2>Meridian Savings Bank &#8212; HomeBank</h2>
<h3>Please confirm this transfer</h3>
<p>This transfer is over the online confirmation threshold. Please
review the details below and confirm.</p>
<table>
  <tr><td class="lbl">From account:</td><td><%= fromAcct %></td></tr>
  <tr><td class="lbl">To account:</td><td><%= toAcct %></td></tr>
  <tr><td class="lbl">Amount:</td><td><%= amount %></td></tr>
  <tr><td class="lbl">Memo:</td><td><%= memo %></td></tr>
</table>
<form method="post" action="transfer/confirm">
  <input type="hidden" name="fromAcct" value="<%= fromAcct %>">
  <input type="hidden" name="toAcct" value="<%= toAcct %>">
  <input type="hidden" name="amount" value="<%= amount %>">
  <input type="hidden" name="memo" value="<%= memo %>">
  <input type="submit" value="Confirm transfer">
</form>
<p><a href="transfer">Cancel and go back</a></p>
</body>
</html>
