# Publishing to Maven Central

This project uses [sbt-ci-release](https://github.com/sbt/sbt-ci-release) to automate publishing to Maven Central.

## Prerequisites

You've already completed these steps:

1. ✅ Created a Sonatype Central Account
2. ✅ Generated a PGP Key
3. ✅ Added the sbt-ci-release plugin to `project/plugins.sbt`
4. ✅ Set up GitHub Actions secrets

## Required GitHub Secrets

Ensure the following secrets are configured in your GitHub repository settings (Settings → Secrets and variables → Actions):

- `PGP_SECRET`: Your PGP private key (base64 encoded)
- `PGP_PASSPHRASE`: The passphrase for your PGP key
- `SONATYPE_USERNAME`: Your Sonatype username
- `SONATYPE_PASSWORD`: Your Sonatype password or token

### How to encode your PGP key

**IMPORTANT**: The key must be base64 encoded in a **single line** without line breaks.

```bash
# On macOS/Linux - Export and encode WITHOUT line breaks
gpg --armor --export-secret-keys YOUR_EMAIL@example.com | base64 -w 0 | pbcopy

# Alternative if -w 0 doesn't work (macOS)
gpg --armor --export-secret-keys YOUR_EMAIL@example.com | base64 | tr -d '\n' | pbcopy

# On Windows (PowerShell)
gpg --armor --export-secret-keys YOUR_EMAIL@example.com | Out-String | [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_)) | Set-Clipboard
```

Then paste the result as the `PGP_SECRET` secret in GitHub.

**Verification**: The secret should be one long continuous string with no spaces or line breaks.

## How to Release

### For Regular Releases

1. Ensure all changes are merged to `main` and tests pass
2. Create and push a version tag:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
3. The GitHub Action will automatically:
   - Run tests
   - Sign artifacts with your PGP key
   - Publish to Maven Central
   - Create a GitHub release

### For Snapshots (optional)

Snapshots are automatically published on every commit to `main` (if configured). The version will be automatically suffixed with `-SNAPSHOT`.

## Version Naming Convention

Use semantic versioning with the `v` prefix:
- `v0.1.0` - Initial release
- `v0.1.1` - Patch release
- `v0.2.0` - Minor release
- `v1.0.0` - Major release

## Configuration Files

### build.sbt

The following metadata has been added for Maven Central compliance:

```scala
ThisBuild / organization := "io.github.riccardomerolla"
ThisBuild / organizationName := "Riccardo Merolla"
ThisBuild / scmInfo := Some(ScmInfo(...))
ThisBuild / developers := List(Developer(...))
ThisBuild / licenses := List("Apache-2.0" -> ...)
ThisBuild / homepage := Some(url(...))
```

### .github/workflows/ci.yml

The CI workflow:
- Runs tests on multiple Java versions (21)
- Checks code formatting with scalafmt
- Publishes to Maven Central when a version tag is pushed

## Troubleshooting

### "sbt: command not found" in GitHub Actions
- The workflow uses `sbt/setup-sbt@v1` action to install sbt
- Ensure this step is present in your workflow before any sbt commands
- If using a custom workflow, add the setup-sbt action after setup-java

### "base64: invalid input" error during signing
- Your `PGP_SECRET` is not properly base64 encoded
- Re-encode your PGP key ensuring it's a **single line without line breaks**:
  ```bash
  gpg --armor --export-secret-keys YOUR_EMAIL@example.com | base64 -w 0
  ```
- Copy the entire output (should be one very long line)
- Update the `PGP_SECRET` secret in GitHub with this value
- The secret should contain no spaces, no line breaks, just the base64 string

### "Repository for publishing is not specified"
- This means `publishTo` is not configured in build.sbt
- Ensure you have the Sonatype configuration:
  ```scala
  ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
  ThisBuild / publishTo := {
    val nexus = "https://s01.oss.sonatype.org/"
    if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
    else Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }
  ```
- This configuration is already included in the project

### Publishing fails with "unauthorized"
- Verify your `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` secrets are correct
- Ensure your Sonatype account is active and verified

### "401: Unauthorized" during staging repository creation
This error means your Sonatype credentials are incorrect or not properly set:

1. **Verify your Sonatype Central credentials:**
   - Go to: https://central.sonatype.com/
   - Try logging in with your username and password
   - If login fails, reset your password

2. **Check which Sonatype system you're using:**
   - **New Sonatype Central** (recommended): https://central.sonatype.com/
   - **Legacy OSS** (if you created account before 2024): https://s01.oss.sonatype.org/

3. **For Sonatype Central (recommended):**
   - Username: Your email or username
   - Password: Generate a token at https://central.sonatype.com/account
   - Click "Generate User Token"
   - Use the generated token as `SONATYPE_PASSWORD`

4. **For Legacy OSS Sonatype:**
   - Username: Your Sonatype JIRA username
   - Password: Your Sonatype JIRA password
   - Or generate a token at: https://s01.oss.sonatype.org/

5. **Update GitHub Secrets:**
   - Go to: https://github.com/.../settings/secrets/actions
   - Update `SONATYPE_USERNAME` with your username
   - Update `SONATYPE_PASSWORD` with your password or token
   - **Important:** No extra spaces, copy exactly as shown

6. **Test locally (optional):**
   ```bash
   export SONATYPE_USERNAME="your-username"
   export SONATYPE_PASSWORD="your-password-or-token"
   sbt "sonatypeList"
   ```

If you're unsure which system you're using, try the Sonatype Central token first (step 3).

### Signing fails
- Check that `PGP_SECRET` is properly base64 encoded
- Verify `PGP_PASSPHRASE` matches your key's passphrase
- Ensure the PGP key hasn't expired

### "Coordinates already exist"
- Each version can only be published once
- Delete the tag locally and on GitHub, increment the version, and try again:
  ```bash
  git tag -d v0.1.0
  git push origin :refs/tags/v0.1.0
  ```

## Verifying Publication

After publishing:
1. Check [Maven Central Search](https://search.maven.org/) for your artifact (may take 10-30 minutes)
2. Your artifact will be available at:
   ```
   https://repo1.maven.org/maven2/io/github/.../
   ```

## Using the Published Library

Once published, users can add your library to their `build.sbt`:

```scala
libraryDependencies += "io.github...." %% "zio-..." % "0.1.0"
```

## Additional Resources

- [sbt-ci-release documentation](https://github.com/sbt/sbt-ci-release)
- [Sonatype Central documentation](https://central.sonatype.org/publish/publish-guide/)
- [GitHub Actions documentation](https://docs.github.com/en/actions)

