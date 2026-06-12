package example

class CalculatorSuite extends munit.FunSuite:

  test("add") {
    assertEquals(Calculator.add(2, 3), 5)
  }

  test("average of a non-empty list") {
    assertEquals(Calculator.average(List(2, 4)), 3)
  }
