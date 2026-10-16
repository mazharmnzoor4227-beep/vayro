"""Fixture: Python sample for editor regression tests (Milestone 2)."""


class Calculator:
    def __init__(self):
        self.last_result = 0.0

    def add(self, a, b):
        self.last_result = a + b
        return self.last_result

    def reset(self):
        self.last_result = 0.0


def main():
    calculator = Calculator()
    total = calculator.add(2.0, 3.0)
    print(f"total={total}")
    calculator.reset()


if __name__ == "__main__":
    main()
