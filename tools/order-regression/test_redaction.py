import unittest
from redact import redact_line


class RedactionTest(unittest.TestCase):
    def test_generated_and_known_secrets_are_removed(self):
        self.assertEqual('Using generated security password: [REDACTED]\n',
                         redact_line('Using generated security password: abc-123\n', []))
        self.assertEqual('secret=[REDACTED]', redact_line('secret=value123', ['value123']))


if __name__ == '__main__':
    unittest.main()
