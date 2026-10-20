import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    'publish_preview', Path(__file__).resolve().parents[1] / 'publish-preview.py'
)
preview = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preview)
SHA = 'a' * 40
OLD_SHA = 'b' * 40
TAG = 'apk-preview-aaaaaaa-42-1'
OLD_TAG = 'apk-preview-bbbbbbb-41-1'
PREFIX = 'Hoshi-Reader-preview-aaaaaaa-42-1'
MARKER = '<!-- hoshi-apk-preview -->'


class FakeGitHub:
    """External release storage; publication decisions stay in the real publisher."""

    def __init__(self, existing=True):
        self.head = SHA
        self.releases = {
            'v1.4.0': self.release('v1.4.0', prerelease=False, body='Stable'),
            'transcription-audio-example': self.release('transcription-audio-example', body='Components'),
            'apk-preview-ccccccc-39-1': self.release('apk-preview-ccccccc-39-1', body='Unrelated release'),
        }
        self.files = {tag: {'keep.apk': b'keep'} for tag in self.releases}
        self.tags = {tag: 'c' * 40 for tag in self.releases}
        self.owned_tags = set()
        if existing:
            self.releases[OLD_TAG] = self.release(OLD_TAG)
            self.tags[OLD_TAG] = OLD_SHA
            self.owned_tags.add(OLD_TAG)
            self.files[OLD_TAG] = {'old.apk': b'old'}
        self.events = []
        self.fail_upload = None
        self.fail_verify = None
        self.advance_on_upload = False
        self.fail_publish = False
        self.fail_delete_tag = False

    @staticmethod
    def release(tag, draft=False, prerelease=True, body=MARKER):
        return dict(id=tag, tag_name=tag, draft=draft, prerelease=prerelease, body=body)

    def main_sha(self):
        return self.head

    def list_releases(self):
        return list(self.releases.values())

    def ensure_tag(self, tag, sha):
        self.events.append(('tag', tag))
        if tag in self.tags and self.tags[tag] != sha:
            raise ValueError('Existing tag points to another commit')
        self.tags[tag] = sha
        self.owned_tags.add(tag)

    def create_draft(self, tag, sha, notes):
        self.events.append(('draft', tag))
        if tag in self.releases:
            raise AssertionError('Duplicate release')
        self.releases[tag] = self.release(tag, draft=True, body=notes)
        self.files[tag] = {}
        return self.releases[tag]

    def assets(self, release_id):
        return [dict(id=(release_id, name), name=name) for name in self.files[release_id]]

    def upload(self, tag, path):
        self.events.append(('upload', path.name))
        if path.name == self.fail_upload:
            raise RuntimeError('Upload connection reset')
        if path.name in self.files[tag]:
            raise AssertionError('Existing assets must not be clobbered')
        self.files[tag][path.name] = path.read_bytes()
        if self.advance_on_upload:
            self.head = 'c' * 40

    def verify(self, asset, path):
        tag, name = asset['id']
        self.events.append(('verify', name))
        if name == self.fail_verify or self.files[tag][name] != path.read_bytes():
            raise preview.AssetMismatchError('Remote asset checksum mismatch')

    def publish(self, release_id, notes):
        if self.fail_publish:
            raise RuntimeError('Release update failed')
        self.events.append(('publish', release_id))
        self.releases[release_id].update(draft=False, prerelease=True, make_latest='false', body=notes)
        return self.releases[release_id]

    def delete_asset(self, asset_id):
        tag, name = asset_id
        self.events.append(('delete_asset', name))
        del self.files[tag][name]

    def delete_release(self, release_id):
        self.events.append(('delete_release', release_id))
        del self.releases[release_id]
        del self.files[release_id]

    def owned_preview_tags(self):
        return [tag for tag in self.tags if tag in self.owned_tags]

    def delete_tag(self, tag):
        self.events.append(('delete_tag', tag))
        if self.fail_delete_tag:
            raise RuntimeError('Tag deletion failed')
        self.tags.pop(tag, None)


class PublishPreviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        for abi in ('arm64-v8a', 'armeabi-v7a'):
            (self.directory / f'{PREFIX}-{abi}.apk').write_bytes(abi.encode())
        (self.directory / 'LICENSE').write_text('license')
        self.changelog = self.directory / 'CHANGELOG.md'
        self.changelog.write_text('# Changelog\n\n## [Unreleased]\n\n### Added\n\n- Preview feature\n'
                                  '\n## [v1.4.0]\n\n- Old feature\n')

    def publish(self, api):
        return preview.publish_preview(
            api, directory=self.directory, sha=SHA, build_id='42-1', changelog=self.changelog,
            run_url='https://github.com/example/repo/actions/runs/42', built_at='2026-10-20T10:00:00Z',
        )

    def test_new_unique_release_is_published_only_after_both_apks_are_verified(self):
        api = FakeGitHub(existing=False)
        self.assertTrue(self.publish(api))
        self.assertFalse(api.releases[TAG]['draft'])
        self.assertTrue(api.releases[TAG]['prerelease'])
        self.assertEqual('false', api.releases[TAG]['make_latest'])
        self.assertEqual(SHA, api.tags[TAG])
        self.assertEqual(3, len(api.files[TAG]))
        self.assertLess(api.events.index(('verify', f'{PREFIX}-armeabi-v7a.apk')),
                        api.events.index(('publish', TAG)))
        notes = api.releases[TAG]['body']
        for text in (SHA, '2026-10-20T10:00:00Z', 'Preview feature'):
            self.assertIn(text, notes)
        self.assertNotIn('Old feature', notes)
        visible, opening, folded = notes.partition('<details>\n')
        self.assertTrue(opening)
        self.assertIn('> [!NOTE]\n> **Development preview**', visible)
        self.assertIn(SHA, visible)
        self.assertIn('[Build workflow]', visible)
        self.assertNotIn('Preview feature', visible)
        self.assertEqual(
            '<summary>Unreleased changes</summary>\n\n'
            '### Added\n\n- Preview feature\n\n</details>\n',
            folded,
        )

    def test_deletes_only_owned_previous_release_and_tag_after_new_publication(self):
        api = FakeGitHub()
        self.assertTrue(self.publish(api))
        self.assertNotIn(OLD_TAG, api.releases)
        self.assertNotIn(OLD_TAG, api.tags)
        for tag in ('v1.4.0', 'transcription-audio-example', 'apk-preview-ccccccc-39-1'):
            self.assertIn(tag, api.releases)
            self.assertIn(tag, api.tags)
            self.assertEqual({'keep.apk': b'keep'}, api.files[tag])
        self.assertLess(api.events.index(('publish', TAG)), api.events.index(('delete_release', OLD_TAG)))

    def test_failed_upload_keeps_old_release_and_tag_and_retry_resumes_draft(self):
        api = FakeGitHub()
        api.fail_upload = f'{PREFIX}-armeabi-v7a.apk'
        with self.assertRaisesRegex(RuntimeError, 'connection reset'):
            self.publish(api)
        self.assertEqual(OLD_SHA, api.tags[OLD_TAG])
        self.assertFalse(api.releases[OLD_TAG]['draft'])
        self.assertTrue(api.releases[TAG]['draft'])
        api.fail_upload = None
        self.assertTrue(self.publish(api))
        self.assertEqual(1, api.events.count(('draft', TAG)))
        self.assertEqual(1, api.events.count(('upload', f'{PREFIX}-arm64-v8a.apk')))

    def test_failed_verification_keeps_old_release(self):
        api = FakeGitHub()
        api.fail_verify = f'{PREFIX}-armeabi-v7a.apk'
        with self.assertRaisesRegex(RuntimeError, 'checksum'):
            self.publish(api)
        self.assertIn(OLD_TAG, api.releases)
        self.assertIn(OLD_TAG, api.tags)
        self.assertTrue(api.releases[TAG]['draft'])

    def test_corrupt_draft_asset_is_repaired_but_published_asset_is_never_removed(self):
        for draft in (True, False):
            with self.subTest(draft=draft):
                api = FakeGitHub()
                api.releases[TAG] = api.release(TAG, draft=draft)
                api.files[TAG] = {f'{PREFIX}-arm64-v8a.apk': b'interrupted upload'}
                api.tags[TAG] = SHA
                if draft:
                    self.assertTrue(self.publish(api))
                    self.assertEqual(b'arm64-v8a', api.files[TAG][f'{PREFIX}-arm64-v8a.apk'])
                else:
                    with self.assertRaises(preview.AssetMismatchError):
                        self.publish(api)
                    self.assertIn(OLD_TAG, api.releases)
                    self.assertEqual(b'interrupted upload', api.files[TAG][f'{PREFIX}-arm64-v8a.apk'])

    def test_stale_commit_has_no_side_effects(self):
        api = FakeGitHub()
        api.head = 'c' * 40
        self.assertFalse(self.publish(api))
        self.assertEqual([], api.events)

    def test_head_change_during_upload_keeps_previous_release(self):
        api = FakeGitHub()
        api.advance_on_upload = True
        self.assertFalse(self.publish(api))
        self.assertIn(OLD_TAG, api.releases)
        self.assertTrue(api.releases[TAG]['draft'])

    def test_publication_failure_preserves_old_release_and_tag(self):
        api = FakeGitHub()
        api.fail_publish = True
        with self.assertRaisesRegex(RuntimeError, 'Release update failed'):
            self.publish(api)
        self.assertIn(OLD_TAG, api.releases)
        self.assertEqual(OLD_SHA, api.tags[OLD_TAG])
        api.fail_publish = False
        self.assertTrue(self.publish(api))

    def test_cleanup_retry_removes_orphan_tag_without_republishing_or_reuploading(self):
        api = FakeGitHub()
        api.fail_delete_tag = True
        with self.assertRaisesRegex(RuntimeError, 'Tag deletion failed'):
            self.publish(api)
        self.assertFalse(api.releases[TAG]['draft'])
        self.assertIn(OLD_TAG, api.tags)
        api.fail_delete_tag = False
        self.assertTrue(self.publish(api))
        self.assertNotIn(OLD_TAG, api.tags)
        self.assertEqual(1, api.events.count(('publish', TAG)))
        self.assertEqual(1, api.events.count(('upload', f'{PREFIX}-arm64-v8a.apk')))

    def test_tag_collision_or_missing_apk_fails_without_overwriting_existing_content(self):
        api = FakeGitHub()
        api.tags[TAG] = OLD_SHA
        with self.assertRaisesRegex(ValueError, 'another commit'):
            self.publish(api)
        self.assertEqual(OLD_SHA, api.tags[TAG])
        self.assertIn(OLD_TAG, api.releases)
        (self.directory / f'{PREFIX}-armeabi-v7a.apk').unlink()
        api = FakeGitHub()
        with self.assertRaises(FileNotFoundError):
            self.publish(api)
        self.assertEqual([], api.events)

    def test_unrelated_orphan_tag_with_similar_name_is_preserved(self):
        api = FakeGitHub()
        api.tags['apk-preview-ddddddd-10-1'] = 'd' * 40
        self.assertTrue(self.publish(api))
        self.assertIn('apk-preview-ddddddd-10-1', api.tags)


class GitHubContractTest(unittest.TestCase):
    def test_draft_and_publication_keep_preview_flags_without_changing_latest(self):
        api = preview.GitHub('example/repo')
        with patch.object(api, 'request') as request:
            api.create_draft(TAG, SHA, MARKER)
            request.assert_called_once_with('POST', 'releases', {
                'tag_name': TAG, 'target_commitish': SHA, 'name': 'Hoshi Reader Preview',
                'body': MARKER, 'draft': True, 'prerelease': True, 'make_latest': 'false',
            })
            request.reset_mock()
            api.publish(123, MARKER)
            request.assert_called_once_with('PATCH', 'releases/123', {
                'body': MARKER, 'draft': False, 'prerelease': True, 'make_latest': 'false',
            })

    def test_orphan_cleanup_requires_annotated_tag_ownership_and_matching_source(self):
        api = preview.GitHub('example/repo')
        refs = [
            dict(ref='refs/tags/apk-preview-aaaaaaa-1-1', object=dict(type='tag', sha='1' * 40)),
            dict(ref='refs/tags/apk-preview-bbbbbbb-2-1', object=dict(type='tag', sha='2' * 40)),
            dict(ref='refs/tags/apk-preview-ccccccc-3-1', object=dict(type='commit', sha='c' * 40)),
        ]
        responses = {
            'git/matching-refs/tags/apk-preview-': refs,
            'git/tags/' + '1' * 40: dict(tag='apk-preview-aaaaaaa-1-1',
                                        message=MARKER, object=dict(type='commit', sha=SHA)),
            'git/tags/' + '2' * 40: dict(tag='apk-preview-bbbbbbb-2-1',
                                        message='Human-created tag', object=dict(type='commit', sha=OLD_SHA)),
        }
        with patch.object(api, 'request', side_effect=lambda method, path: responses[path]):
            self.assertEqual(['apk-preview-aaaaaaa-1-1'], api.owned_preview_tags())


if __name__ == '__main__':
    unittest.main()
