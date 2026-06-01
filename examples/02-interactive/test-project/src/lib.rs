//! A tiny calculator crate. The 02-interactive example points an open-ended
//! prompt at it ("make the calculator more useful"), so the planner asks a
//! clarifying question before producing a plan.

pub fn add(a: i64, b: i64) -> i64 {
    a + b
}

pub fn subtract(a: i64, b: i64) -> i64 {
    a - b
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adds() {
        assert_eq!(add(2, 3), 5);
    }
}
