//! A tiny calculator crate. Deliberately missing a `multiply` function so the
//! example flow has something concrete to implement.

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

    #[test]
    fn subtracts() {
        assert_eq!(subtract(5, 3), 2);
    }
}
