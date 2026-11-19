"""Remove known ephemeral credentials and Spring's generated password from logs."""
import re


def redact_line(line, secrets):
    line = re.sub(r'(Using generated security password:\s*)\S+', r'\1[REDACTED]', line)
    for secret in secrets:
        if secret:
            line = line.replace(secret, '[REDACTED]')
    return line
