"""Runtime result contract tests; Android behavior itself is checked on the emulator."""
import unittest
from release_runtime import instrumentation_passes


class RuntimeResultTests(unittest.TestCase):
    output = ('INSTRUMENTATION_STATUS: class=example.Test\nINSTRUMENTATION_STATUS: test=works\n'
              'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n')

    def test_complete_exact_results_pass(self):
        """Test: A completed expected test passes. Why: The gate must allow valid Android runner output."""
        self.assertEqual(['example.Test#works'], instrumentation_passes(self.output, ['example.Test#works']))

    def test_false_success_is_rejected(self):
        """Test: Zero/partial runs, skips, assumptions, failures and crashes fail even with an OK summary.
        Why: ADB success and JUnit summaries alone do not prove the required tests executed.
        """
        invalid = ['OK (0 tests)', 'OK (1 test)', self.output.replace('0\nOK', '-2\nOK'),
                   self.output.replace('0\nOK', '-3\nOK'), self.output.replace('0\nOK', '-4\nOK'),
                   self.output.replace('1 test', '2 tests'), self.output + 'INSTRUMENTATION_FAILED: crash',
                   self.output + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.']
        for output in invalid:
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                instrumentation_passes(output)

    def test_unexpected_or_missing_method_is_rejected(self):
        """Test: A successful different test cannot replace a required test. Why: Selection errors must fail closed."""
        with self.assertRaisesRegex(RuntimeError, 'tests are missing'):
            instrumentation_passes(self.output, ['example.Test#required'])


if __name__ == '__main__':
    unittest.main()
