"""Release guards must reject mismatched tags, coordinates and incomplete uploads."""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    path = Path(__file__).with_name(name + '.py')
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = load('validate-release')
verify = load('verify-published')


class ReleaseGuardTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.versions = {f'{module}.publishVersion': '1.2.3' for module in release.ARTIFACTS}
        self.poms = {}
        self.paths = []
        dependency = next(iter(release.ARTIFACTS.values()))
        for module, artifact in release.ARTIFACTS.items():
            path = Path(self.folder.name) / f'{artifact}.pom'
            path.write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
<groupId>com.worxbend</groupId><artifactId>{artifact}</artifactId><version>1.2.3</version>
<dependencies><dependency><groupId>com.worxbend</groupId><artifactId>{dependency}</artifactId>
<version>1.2.3</version></dependency></dependencies></project>''')
            self.paths.append(path)
            self.poms[f'{module}.pom'] = f'ref:v0:hash:{path}'
        self.environment = {'GITHUB_REF_TYPE': 'tag', 'GITHUB_REF_NAME': 'v1.2.3',
                            'GITHUB_SHA': 'commit', 'VALIDATED_SHA': 'commit'}
        self.tagged_commit = 'commit'
        self.env = patch.dict(os.environ, self.environment)
        self.env.start()
        self.addCleanup(self.env.stop)

    def output(self, *args):
        if args[0] == 'git':
            return 'commit' if args[-1] == 'HEAD' else self.tagged_commit
        return json.dumps(self.versions if args[-1].endswith('.publishVersion') else self.poms)

    def run_guard(self):
        with patch.object(release, 'output', side_effect=self.output):
            release.main()

    def test_matching_release(self):
        self.run_guard()

    def test_branch_and_snapshot_rejected(self):
        for changes in ({'GITHUB_REF_TYPE': 'branch'}, {'GITHUB_REF_NAME': 'v1.2.3-SNAPSHOT'},
                        {'GITHUB_REF_NAME': 'v1.2.3;echo'}):
            with self.subTest(changes=changes), patch.dict(os.environ, changes):
                with self.assertRaises(ValueError):
                    self.run_guard()

    def test_validated_and_tag_commits_must_match(self):
        with patch.dict(os.environ, {'VALIDATED_SHA': 'other'}):
            with self.assertRaises(ValueError):
                self.run_guard()
        self.tagged_commit = 'other'
        with self.assertRaises(ValueError):
            self.run_guard()

    def test_one_wrong_module_version_rejected(self):
        self.versions[next(iter(self.versions))] = '9.9.9'
        with self.assertRaises(ValueError):
            self.run_guard()

    def test_wrong_group_and_internal_dependency_rejected(self):
        original = self.paths[0].read_text()
        for xml in (original.replace('com.worxbend', 'io.worxbend', 1),
                    original.replace('<version>1.2.3</version></dependency>',
                                     '<version>0.0.1</version></dependency>')):
            with self.subTest(xml=xml):
                self.paths[0].write_text(xml)
                with self.assertRaises(ValueError):
                    self.run_guard()


class PublishedArtifactsTest(unittest.TestCase):
    def test_requires_all_four_files_and_exact_coordinates(self):
        artifact = next(iter(verify.ARTIFACTS.values()))
        data = {'meta': {'group': 'com.worxbend', 'id': artifact, 'version': '1.2.3'},
                'payload': [[f'ref:v0:hash:/tmp/{suffix}', f'{artifact}-1.2.3{suffix}']
                            for suffix in ('.pom', '.jar', '-sources.jar', '-javadoc.jar')]}
        self.assertEqual(len(verify.artifact_files(data, artifact, '1.2.3')), 4)
        with self.assertRaises(ValueError):
            verify.artifact_files(data, artifact, '9.9.9')
        data['payload'].pop()
        with self.assertRaises(ValueError):
            verify.artifact_files(data, artifact, '1.2.3')

    def test_credentials_restricted_to_own_github_repository(self):
        self.assertEqual(verify.request_headers(verify.CENTRAL), {})
        with self.assertRaises(ValueError):
            verify.request_headers('https://example.com/maven')
        with self.assertRaises(ValueError):
            verify.request_headers('https://maven.pkg.github.com/someone/else')


if __name__ == '__main__':
    unittest.main()
