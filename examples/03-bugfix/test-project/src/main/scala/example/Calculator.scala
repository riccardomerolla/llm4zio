package example

object Calculator:

  def add(a: Int, b: Int): Int = a + b

  def subtract(a: Int, b: Int): Int = a - b

  /** Arithmetic mean of a list of integers.
    *
    * BUG: this throws `ArithmeticException` on an empty list (divide by zero)
    * instead of handling it. A good bug for the issue-driven flow to reproduce
    * with a failing test and then fix.
    */
  def average(nums: List[Int]): Int =
    nums.sum / nums.size
