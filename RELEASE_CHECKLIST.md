# Release Checklist

Use this checklist when preparing a new release of zio-toon.

## Pre-Release

- [ ] All tests passing locally: `sbt test`
- [ ] Code formatted: `sbt scalafmtAll`
- [ ] Documentation updated in README.md
- [ ] CHANGELOG.md updated with new version and changes
- [ ] All changes committed to `main` branch
- [ ] GitHub Actions CI is green on main

## Release Process

1. **Decide on version number** following semantic versioning:
   - Patch: `v0.1.1` - Bug fixes, no API changes
   - Minor: `v0.2.0` - New features, backward compatible
   - Major: `v1.0.0` - Breaking changes

2. **Create and push the tag**:
   ```bash
   git checkout main
   git pull origin main
   git tag -a v0.1.0 -m "Release version 0.1.0"
   git push origin v0.1.0
   ```

3. **Monitor the release**:
   - Go to Actions tab in GitHub: https://github.com/.../actions
   - Watch the CI workflow complete
   - Verify the "Publish to Maven Central" job succeeds

4. **Verify publication** (10-30 minutes after release):
   - Check Maven Central: https://search.maven.org/search?q=g:io.github....%20AND%20a:zio-..*
   - Check GitHub Releases: https://github.com/.../releases

## Post-Release

- [ ] Verify artifact appears on Maven Central
- [ ] Update README.md if version in examples needs updating
- [ ] Announce release (if applicable)
- [ ] Create GitHub release notes with highlights

## Rollback

If something goes wrong:

1. **Delete the tag**:
   ```bash
   git tag -d v0.1.0
   git push origin :refs/tags/v0.1.0
   ```

2. **Fix the issue**

3. **Create a new patch version**:
   ```bash
   git tag -a v0.1.1 -m "Release version 0.1.1"
   git push origin v0.1.1
   ```

Note: You cannot republish the same version to Maven Central. Once a version is published, it's permanent.

## Emergency Hotfix

For critical bugs in production:

1. **Create hotfix branch from tag**:
   ```bash
   git checkout -b hotfix/0.1.1 v0.1.0
   ```

2. **Make fix and commit**

3. **Tag and push**:
   ```bash
   git tag -a v0.1.1 -m "Hotfix: critical bug fix"
   git push origin v0.1.1
   ```

4. **Merge back to main**:
   ```bash
   git checkout main
   git merge hotfix/0.1.1
   git push origin main
   ```

## Troubleshooting

See [PUBLISHING.md](PUBLISHING.md) for detailed troubleshooting steps.

