import gzip
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("release_manage", Path(__file__).with_name("manage.py"))
manage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(manage)


class ReleaseManagementTest(unittest.TestCase):
    def test_init_generates_distinct_secrets_and_refuses_to_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            previous = manage.ENV_FILE
            manage.ENV_FILE = Path(temp) / ".env"
            try:
                manage.initialize()
                values, _ = manage.settings()
                passwords = [values[name] for name in
                             ("IM_MYSQL_ROOT_PASSWORD", "IM_MYSQL_PASSWORD", "IM_PGVECTOR_PASSWORD")]
                self.assertEqual(len(set(passwords)), 3)
                with self.assertRaises(RuntimeError):
                    manage.initialize()
            finally:
                manage.ENV_FILE = previous

    def test_env_rejects_placeholder_reused_secrets_and_partial_smtp(self):
        with tempfile.TemporaryDirectory() as temp:
            previous = manage.ENV_FILE
            manage.ENV_FILE = Path(temp) / ".env"
            try:
                shared = "a" * 20
                manage.ENV_FILE.write_text(
                    f"IM_MYSQL_ROOT_PASSWORD={shared}\nIM_MYSQL_PASSWORD={shared}\n"
                    f"IM_PGVECTOR_PASSWORD={'c' * 20}\n", encoding="utf-8")
                with self.assertRaises(RuntimeError):
                    manage.settings()
                manage.ENV_FILE.write_text(
                    f"IM_MYSQL_ROOT_PASSWORD={'a' * 20}\nIM_MYSQL_PASSWORD={'b' * 20}\n"
                    f"IM_PGVECTOR_PASSWORD={'c' * 20}\nIM_SMTP_HOST=mail.example.com\n", encoding="utf-8")
                with self.assertRaises(RuntimeError):
                    manage.settings()
                manage.ENV_FILE.write_text(
                    f"IM_MYSQL_ROOT_PASSWORD={'a' * 20}\nIM_MYSQL_PASSWORD={'b' * 20}\n"
                    f"IM_PGVECTOR_PASSWORD={'c' * 20}\nIM_RELEASE_PORT=18080\n", encoding="utf-8")
                self.assertEqual(manage.settings()[1], 18080)
            finally:
                manage.ENV_FILE = previous

    def test_local_code_mode_is_explicit_and_requires_boolean_value(self):
        with tempfile.TemporaryDirectory() as temp:
            previous = manage.ENV_FILE
            manage.ENV_FILE = Path(temp) / ".env"
            try:
                base = (f"IM_MYSQL_ROOT_PASSWORD={'a' * 20}\n"
                        f"IM_MYSQL_PASSWORD={'b' * 20}\n"
                        f"IM_PGVECTOR_PASSWORD={'c' * 20}\n")
                manage.ENV_FILE.write_text(base, encoding="utf-8")
                self.assertNotIn("IM_LOCAL_TEST_CODES", manage.settings()[0])
                manage.ENV_FILE.write_text(base + "IM_LOCAL_TEST_CODES=true\n", encoding="utf-8")
                self.assertEqual(manage.settings()[0]["IM_LOCAL_TEST_CODES"], "true")
                manage.ENV_FILE.write_text(base + "IM_LOCAL_TEST_CODES=yes\n", encoding="utf-8")
                with self.assertRaises(RuntimeError):
                    manage.settings()
            finally:
                manage.ENV_FILE = previous

    def test_backup_stream_is_compressed_and_restore_stream_is_decoded(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "sample.sql.gz"
            restored = Path(temp) / "restored.sql"
            content = ("INSERT INTO example VALUES ('安全');\n" * 500).encode("utf-8")
            manage.dump([sys.executable, "-c", "import sys; sys.stdout.buffer.write(" + repr(content) + ")"], path)
            with gzip.open(path, "rb") as source:
                self.assertEqual(source.read(), content)
            self.assertEqual(len(manage.checksum(path)), 64)
            manage.restore_file([sys.executable, "-c",
                                 "import sys; open(sys.argv[1], 'wb').write(sys.stdin.buffer.read())",
                                 str(restored)], path)
            self.assertEqual(restored.read_bytes(), content)


if __name__ == "__main__":
    unittest.main()
