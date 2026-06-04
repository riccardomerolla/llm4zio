//! A tiny calculator crate. The 05-interactive-live example points an open-ended
//! prompt at it ("make the calculator more useful"): the planner asks a clarifying
//! question, then a live `claude` session implements each task — streaming its work,
//! asking follow-ups, and routing tool calls through an approval gate.

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
