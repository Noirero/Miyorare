#!/usr/bin/env python3
"""Exercise report guards and Bash failure propagation without Android/credentials."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from runtime_acceptance_evidence import junit_summary, require_junit, require_probe_manifest

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / '.github/acceptance'
CLASS = 'org.koitharu.kotatsu.sync.library.LibrarySyncPersistenceTest'
RUNNER = 'org.koitharu.kotatsu.HiltTestRunner'
PACKAGE = 'org.noirero.miyorare'


class EvidenceTests(unittest.TestCase):
    def test_alignment_uses_production_baseline_only_for_probe_classpaths(self):
        # Structural check; resolving both Gradle/AGP probe graphs is still required.
        script = (TOOLS / 'runtime-probe.init.gradle').read_text()
        self.assertIn("before.eachLine { line ->", script)
        self.assertIn('def alignedVersions = productionVersions + testOnlyVersions', script)
        self.assertIn('version { strictly(selectedVersion) }', script)
        self.assertIn('configuration.extendsFrom(alignment)', script)
        self.assertIn("['releaseAndroidTestCompileClasspath', 'releaseAndroidTestRuntimeClasspath']", script)
        self.assertEqual(script.count('extendsFrom(alignment)'), 1)
        self.assertIn('Probe version drift in', script)
        self.assertIn('Probe compile/runtime version drift:', script)
        self.assertNotIn('production.extendsFrom', script)
        self.assertNotIn('resolutionStrategy.force', script)
        self.assertIn("getByName('androidComponents').sdkComponents", script)
        self.assertIn('sdkComponents.bootClasspath.get()', script)
        self.assertNotIn('android.compileSdkVersion', script)

    def test_probe_filters_external_artifacts_before_resolution(self):
        # Structural regression guard only; actual Gradle/AGP builds remain required.
        script = (TOOLS / 'runtime-probe.init.gradle').read_text()
        self.assertNotIn('resolvedConfiguration', script)
        graph = script.index('text = graph(configuration)')
        view = script.index('configuration.incoming.artifactView {', graph)
        predicate = script.index('componentFilter { it instanceof ModuleComponentIdentifier }', view)
        strict = script.index('lenient = false', predicate)
        resolution = script.index('externalArtifacts.artifacts.artifacts', strict)
        guard = script.index('if (providers.isEmpty())', resolution)
        self.assertLess(graph, view)
        self.assertLess(predicate, resolution)
        self.assertIn('containsClass(it.file, entry)', script[resolution:guard])
        self.assertIn('throw new GradleException', script[guard:])
        self.assertIn('${configuration.name}-class-presence.txt', script[resolution:guard])
        self.assertIn('${it.id.componentIdentifier.displayName} ${it.file.name}', script[resolution:])

    def test_reports_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            with self.assertRaises(ValueError):
                require_junit(junit_summary(path, CLASS, 3))
            for marker in ('', '<skipped/>', '<failure/>', '<error/>'):
                cases = ''.join(f'<testcase classname="{CLASS}" name="test{i}">{marker if i == 0 else ""}</testcase>' for i in range(3))
                (path / 'TEST-sync.xml').write_text(f'<testsuite>{cases}</testsuite>')
                summary = junit_summary(path, CLASS, 3)
                self.assertEqual(summary['tests'], 3)
                if marker:
                    with self.assertRaises(ValueError):
                        require_junit(summary)
                else:
                    require_junit(summary)
            (path / 'TEST-sync.xml').write_text(f'<testsuite><testcase classname="{CLASS}" name="same"/><testcase classname="{CLASS}" name="same"/><testcase classname="{CLASS}" name="same"/></testsuite>')
            with self.assertRaises(ValueError):
                require_junit(junit_summary(path, CLASS, 3))

    def test_wrong_class_and_malformed_xml_retain_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            output = path / 'summary.json'
            for content in ('<testsuite><testcase classname="WrongClass" name="test"/></testsuite>', 'broken XML'):
                (path / 'TEST-sync.xml').write_text(content)
                result = subprocess.run(['python3', str(ROOT / '.github/scripts/runtime_acceptance_evidence.py'), 'junit', '--results', str(path), '--class-name', CLASS, '--expected-count', '3', '--output', str(output)], capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(json.loads(output.read_text())['tests'], 0)

    def test_manifest_exact_target_and_runner(self):
        def manifest(package=PACKAGE, runner=RUNNER):
            return f'  E: instrumentation (line=1)\n    A: android:name(0x01010003)="{runner}" (Raw: "{runner}")\n    A: android:targetPackage(0x01010021)="{package}" (Raw: "{package}")\n  E: application (line=2)\n'
        require_probe_manifest(manifest(), PACKAGE, RUNNER)
        require_probe_manifest(manifest().split('  E: application')[0].rstrip('\n'), PACKAGE, RUNNER)
        for text in (manifest(PACKAGE + '.debug'), manifest(runner='androidx.test.runner.AndroidJUnitRunner'), manifest() + manifest(), ''):
            with self.assertRaises(ValueError):
                require_probe_manifest(text, PACKAGE, RUNNER)


class ShellTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.bin = self.work / 'bin'
        self.bin.mkdir()
        self.evidence = self.work / 'evidence'
        self.evidence.mkdir()
        self.preserved = self.work / 'preserved'
        self.preserved.mkdir()
        for path in TOOLS.iterdir():
            if path.is_file():
                (self.preserved / path.name).write_bytes(path.read_bytes())
        (self.preserved / 'runtime_acceptance_evidence.py').write_bytes((ROOT / '.github/scripts/runtime_acceptance_evidence.py').read_bytes())
        self.env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}', CANDIDATE_SHA='candidate', STABLE_COMMIT='stable', HARNESS_TOOLS=str(self.preserved), ACCEPTANCE_EVIDENCE=str(self.evidence), PACKAGE=PACKAGE, STABLE_SHA256='hash', EXPECTED_CANDIDATE_APK_SHA256='hash', RELEASE_STORE_FILE='/not-a-real-keystore', RELEASE_STORE_PASSWORD='fake-secret-password', RELEASE_KEY_ALIAS='fake-secret-alias', RELEASE_KEY_PASSWORD='fake-secret-key')
        self.write_executable(self.bin / 'git', '#!/bin/bash\nif [[ "$1" == checkout ]]; then echo "$3" > .mock-head; else cat .mock-head; fi\n')
        (self.work / '.mock-head').write_text('candidate\n')
        self.write_executable(self.bin / 'adb', '#!/bin/bash\necho mock-adb\n')
        self.write_executable(self.bin / 'sha256sum', '#!/bin/bash\ncat >/dev/null\nexit 0\n')

    def write_executable(self, path, text):
        path.write_text(text)
        path.chmod(0o755)

    def invoke(self, script):
        # Exactly the action's /usr/bin/sh -> explicit Bash boundary.
        return subprocess.run(['/usr/bin/sh', '-c', 'bash "$HARNESS_TOOLS/' + script + '"'], cwd=self.work, env=self.env, text=True, capture_output=True)

    def reports(self):
        path = self.work / 'app/build/outputs/androidTest-results/connected'
        path.mkdir(parents=True)
        (path / 'TEST-sync.xml').write_text('<testsuite>' + ''.join(f'<testcase classname="{CLASS}" name="test{i}"/>' for i in range(3)) + '</testsuite>')

    def test_both_emulator_shells_syntax(self):
        for name in ('library-sync.sh', 'upgrade-release.sh', 'build-probes.sh'):
            subprocess.run(['bash', '-n', str(TOOLS / name)], check=True)

    def test_sync_requires_execution_and_preserves_pipeline_exit(self):
        self.write_executable(self.work / 'gradlew', '#!/bin/bash\necho gradle-log\nexit 0\n')
        result = self.invoke('library-sync.sh')
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads((self.evidence / 'test-summary.json').read_text())['tests'], 0)
        self.reports()
        result = self.invoke('library-sync.sh')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads((self.evidence / 'test-summary.json').read_text())['tests'], 3)
        self.write_executable(self.work / 'gradlew', '#!/bin/bash\necho failed-gradle-log\nexit 23\n')
        result = self.invoke('library-sync.sh')
        self.assertEqual(result.returncode, 23)
        self.assertIn('failed-gradle-log', (self.evidence / 'gradle-instrumentation.log').read_text())
        self.assertTrue((self.evidence / 'androidTest-results/TEST-sync.xml').is_file())

    def test_upgrade_retains_install_failure_and_diagnostics(self):
        self.write_executable(self.bin / 'adb', '#!/bin/bash\necho adb-log\nif [[ "$1" == install ]]; then exit 24; fi\n')
        result = self.invoke('upgrade-release.sh')
        self.assertEqual(result.returncode, 24, result.stdout + result.stderr)
        self.assertEqual((self.evidence / 'exit-status.txt').read_text(), 'exit_code=24\n')
        self.assertIn('adb-log', (self.evidence / 'stable-install.txt').read_text())
        self.assertTrue((self.evidence / 'release-logcat.txt').is_file())

    def test_probe_failure_propagation_and_log_redaction(self):
        self.write_executable(self.work / 'gradlew', '#!/bin/bash\necho "failure fake-secret-password fake-secret-key"\nexit 25\n')
        result = self.invoke('build-probes.sh')
        self.assertEqual(result.returncode, 25, result.stdout + result.stderr)
        self.assertEqual((self.evidence / 'probe-stable/source.txt').read_text(), 'source_sha=stable\n')
        log = (self.evidence / 'probe-stable/production-baseline.log').read_text()
        self.assertNotIn('fake-secret', log)
        self.assertIn('failure *** ***', log)
        self.assertFalse((self.evidence / 'probe-candidate').exists())

    def test_probe_r8_failure_retains_allowlisted_diagnostics_without_secrets(self):
        self.write_executable(self.work / 'gradlew', '''#!/bin/bash
if [[ "$*" == *assembleReleaseAndroidTest* ]]; then
  mkdir -p app/build/outputs/mapping/releaseAndroidTest
  printf 'missing fingerprint\\n' > app/build/outputs/mapping/releaseAndroidTest/missing_rules.txt
  printf 'config fake-secret-password fake-secret-alias fake-secret-key /not-a-real-keystore\\n' > app/build/outputs/mapping/releaseAndroidTest/configuration.txt
  printf 'private signing bytes\\n' > app/build/outputs/mapping/releaseAndroidTest/release.keystore
  exit 26
fi
exit 0
''')
        result = self.invoke('build-probes.sh')
        self.assertEqual(result.returncode, 26, result.stdout + result.stderr)
        self.assertEqual((self.evidence / 'probe-build-exit-status.txt').read_text(), 'exit_code=26\n')
        diagnostics = self.evidence / 'probe-stable/r8/releaseAndroidTest'
        self.assertEqual((diagnostics / 'missing_rules.txt').read_text(), 'missing fingerprint\n')
        self.assertEqual((diagnostics / 'configuration.txt').read_text(), 'config *** *** *** ***\n')
        self.assertFalse((diagnostics / 'release.keystore').exists())
        for classpath in ('releaseAndroidTestCompileClasspath', 'releaseAndroidTestRuntimeClasspath'):
            self.assertTrue((self.evidence / f'probe-stable/dependency-insight-{classpath}.log').is_file())


if __name__ == '__main__':
    unittest.main()
