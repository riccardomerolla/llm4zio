//! A tiny calculator crate with an open-ended starting point — the interactive
//! examples aim a vague prompt at it so the planner has to ask questions.

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
