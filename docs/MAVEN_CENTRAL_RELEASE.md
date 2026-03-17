# MAVEN_CENTRAL_RELEASE.md

Complete guide for publishing JOTP to Maven Central using the Sonatype Central Portal and the `central-publishing-maven-plugin`.

## Quick Start

```bash
# 1. Setup credentials (one-time)
cp ~/.m2/settings.xml.example ~/.m2/settings.xml
# Edit to add your token and GPG key

# 2. Prepare release
git tag -a v2026.1.0 -m "Release 2026.1.0"

# 3. Build & sign
mvn clean verify
mvn clean package -Prelease

# 4. Deploy to Maven Central
mvn clean deploy -Prelease

# 5. Monitor deployment
# Visit: https://central.sonatype.com/publish/

# 6. Verify after sync (~30 minutes)
mvn dependency:get -Dartifact=io.github.seanchatmangpt:jotp:2026.1.0
```

---

## Prerequisites

### System Requirements

- **Java 26** with `--enable-preview` flag support
- **Maven 4** or Maven Wrapper (`./mvnw`)
- **mvnd** (optional, 30% faster builds)
- **Git** (for tagging and version control)
- **GPG** (for artifact signing)
- **curl** (for validation)

### Credentials Required

#### 1. Sonatype Central Account

1. Sign up at: https://central.sonatype.com
2. Create a **User Token** with `write` permission:
   - Go to: Settings → User Tokens
   - Click "Generate Token"
   - Copy the token (format: `username:password`)
3. Save the token securely (you'll need it in `settings.xml`)

#### 2. GPG Key Pair

Generate a signing key if you don't have one:

```bash
# Generate key (interactive)
gpg --full-generate-key

# List your keys
gpg --list-secret-keys --keyid-format LONG

# Export key ID from output (last 16 hex chars)
# Example: sec   rsa4096/7CFFA7E0ABCDEF01
#                              ^^^^^^^^^
#                              Use this ID
```

**Key Requirements for Maven Central:**
- RSA 4096-bit or stronger
- Never expires, or expires well in the future
- Must be uploaded to: https://pgp.mit.edu or https://keys.openpgp.org

```bash
# Export public key to keyserver
gpg --keyserver pgp.mit.edu --send-keys YOUR_KEY_ID

# Verify upload (wait ~1 minute)
gpg --keyserver pgp.mit.edu --recv-keys YOUR_KEY_ID
```

---

## Configuration

### pom.xml Already Configured

The project `pom.xml` already includes:

1. **Release Profile** (line 547-602):
   - `maven-source-plugin` - generates sources JAR
   - `maven-javadoc-plugin` - generates javadoc JAR (already in main)
   - `maven-gpg-plugin` - signs artifacts with GPG
   - `central-publishing-maven-plugin` - publishes to Maven Central

2. **Properties** (line 44-61):
   ```xml
   <gpg.keyname></gpg.keyname>        <!-- Override in settings.xml -->
   <gpg.passphrase></gpg.passphrase>  <!-- Override in settings.xml -->
   <central.token></central.token>     <!-- Override in settings.xml -->
   ```

### Maven Settings Configuration

Create or update `~/.m2/settings.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              https://maven.apache.org/xsd/settings-1.0.0.xsd">

  <!-- Server credentials for Maven Central -->
  <servers>
    <!-- Sonatype Central Portal (post-Feb 2024) -->
    <server>
      <id>central</id>
      <username>seanchatmangpt</username>
      <password>YOUR_SONATYPE_TOKEN_HERE</password>
      <!-- Format: username:password from https://central.sonatype.com/publishing/admin -->
    </server>

    <!-- GPG Passphrase Server (for automated signing) -->
    <server>
      <id>gpg.passphrase</id>
      <passphrase>YOUR_GPG_PASSPHRASE_HERE</passphrase>
    </server>
  </servers>

  <!-- GPG Profile (auto-activated) -->
  <profiles>
    <profile>
      <id>gpg</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <properties>
        <!-- Your GPG key ID (16 hex chars, the last 16 from --list-secret-keys) -->
        <gpg.keyname>YOUR_KEY_ID_HERE</gpg.keyname>
        <!-- Optional: set passphrase for non-interactive signing -->
        <!-- <gpg.passphrase>YOUR_PASSPHRASE_HERE</gpg.passphrase> -->
      </properties>
    </profile>
  </profiles>

  <!-- Proxy Configuration (if behind corporate firewall) -->
  <!-- <proxies>
    <proxy>
      <id>corporate-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>proxy.example.com</host>
      <port>8080</port>
      <username>proxyuser</username>
      <password>proxypass</password>
      <nonProxyHosts>*.example.com|localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies> -->

</settings>
```

**Alternative: Use Environment Variables**

Instead of hardcoding in `settings.xml`, reference environment variables:

```xml
<server>
  <id>central</id>
  <username>${env.SONATYPE_USERNAME}</username>
  <password>${env.SONATYPE_TOKEN}</password>
</server>

<properties>
  <gpg.keyname>${env.GPG_KEYNAME}</gpg.keyname>
  <gpg.passphrase>${env.GPG_PASSPHRASE}</gpg.passphrase>
</properties>
```

Then set:
```bash
export SONATYPE_USERNAME="seanchatmangpt"
export SONATYPE_TOKEN="your_token"
export GPG_KEYNAME="7CFFA7E0ABCDEF01"  # Your 16-char key ID
export GPG_PASSPHRASE="your_passphrase"
```

---

## Release Steps

### Step 1: Verify Build Quality

Ensure all tests pass and code is formatted:

```bash
# Run full test suite
mvn clean verify

# Check code formatting (Google Java Format / AOSP)
mvn spotless:check

# Generate Javadoc without errors
mvn javadoc:javadoc

# Run with preview flag enabled
mvn clean verify --enable-preview
```

**Expected Output:**
- 100+ tests passing
- No format violations
- Javadoc generation successful
- All integration tests pass

### Step 2: Update Version in pom.xml

```bash
# Current version: 2026.1.0
# Determine new version based on changes:
# - Bug fixes only → PATCH (2026.1.0 → 2026.1.1)
# - New features → MINOR (2026.1.0 → 2026.2.0)
# - Breaking changes → MAJOR (2026.2.0 → 2027.0.0)

# Edit pom.xml
nano pom.xml

# Line 10: change version
# <version>2026.1.1</version>
```

**Versioning Scheme:**

JOTP uses calendar-based versioning:
- **YEAR.MONTH.PATCH** format
- Example: `2026.1.0` = Year 2026, Month 1 (January), Patch 0
- Patch increments for bugfixes in the same month

### Step 3: Create Git Tag

```bash
# Create annotated tag (required for Maven Central)
git tag -a v2026.1.0 -m "Release 2026.1.0"

# List tags to verify
git tag -l

# View tag details
git show v2026.1.0
```

**Tag Format:**
- Prefix with `v` (required by pom.xml `tagNameFormat`)
- Use semantic version without leading zeros
- Annotated tags (not lightweight)

### Step 4: Run Final Verification

```bash
# Clean, compile, test, quality checks
mvn clean verify

# Expected: BUILD SUCCESS with 100+ tests
```

### Step 5: Package Release (Signing)

```bash
# Build with release profile (generates sources, javadoc, signs)
mvn clean package -Prelease

# Expected artifacts in target/:
# - jotp-2026.1.0.jar
# - jotp-2026.1.0-sources.jar
# - jotp-2026.1.0-javadoc.jar
# - jotp-2026.1.0.pom
# - *.asc (GPG signatures for each artifact)

# Verify signatures exist
ls -la target/*.asc
# Should see: jotp-*.jar.asc, jotp-*.pom.asc, etc.
```

### Step 6: Verify Artifacts Before Deployment

```bash
# Check JAR contains module-info (Java 26 JPMS)
jar tf target/jotp-2026.1.0.jar | grep module-info

# Verify sources JAR
unzip -l target/jotp-2026.1.0-sources.jar | head -20

# Verify Javadoc JAR
unzip -l target/jotp-2026.1.0-javadoc.jar | head -20

# Check POM is valid
cat target/jotp-2026.1.0.pom | head -30
```

### Step 7: Deploy to Maven Central

```bash
# Deploy with release profile
# This signs artifacts and publishes to Sonatype Central Portal
mvn clean deploy -Prelease

# Expected output:
# [INFO] Uploading to central: https://central.sonatype.com/api/v1/publisher/upload
# [INFO] Uploading: jotp-2026.1.0.jar
# [INFO] Uploading: jotp-2026.1.0-sources.jar
# [INFO] Uploading: jotp-2026.1.0-javadoc.jar
# [INFO] Uploading: jotp-2026.1.0.pom
# [INFO] BUILD SUCCESS
```

**What happens during deploy:**
1. `maven-source-plugin` attaches sources JAR
2. `maven-javadoc-plugin` attaches javadoc JAR
3. `maven-gpg-plugin` signs all artifacts with GPG
4. `central-publishing-maven-plugin`:
   - Creates deployment on Sonatype Central Portal
   - Uploads all artifacts + signatures + POM
   - Validates bundle completeness
   - Auto-publishes (if `autoPublish=true` in pom.xml)
   - Initiates sync to Maven Central mirrors

### Step 8: Monitor Deployment Status

```bash
# Check Sonatype Central Portal
# https://central.sonatype.com/publish/

# Or via API
curl -u "username:token" \
  https://central.sonatype.com/api/v1/publisher/status

# Expected states:
# - VALIDATED: artifacts passed initial checks
# - PUBLISHED: ready for Maven Central sync
# - COMPLETED: synced to Maven Central (30 min after publish)
```

**Dashboard Details:**
1. Log in to https://central.sonatype.com
2. Click "Publish" in left menu
3. Find your deployment by version
4. Status progression:
   - Uploading → Validating → Published → Completed
   - Each step takes 1-5 minutes
   - Maven Central sync takes 10-30 minutes after "Published"

### Step 9: Wait for Maven Central Sync

```bash
# Wait for Central Portal to mark as "COMPLETED"
# This takes 10-30 minutes from "Published" status

# In the meantime, verify locally
mvn dependency:get -Dartifact=io.github.seanchatmangpt:jotp:2026.1.0 -DrepoUrl=https://repo1.maven.org/maven2/

# Once sync completes, Maven can resolve the artifact
# Check metadata
curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/maven-metadata.xml
```

---

## Configuration Details

### maven-gpg-plugin (Signing)

Located in `pom.xml` (release profile, line 567-585):

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-gpg-plugin</artifactId>
  <version>3.2.4</version>
  <executions>
    <execution>
      <id>sign-artifacts</id>
      <phase>verify</phase>
      <goals>
        <goal>sign</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <keyname>${gpg.keyname}</keyname>
    <passphraseServerId>gpg.passphrase</passphraseServerId>
  </configuration>
</plugin>
```

**How it works:**
1. Uses GPG key from `${gpg.keyname}` (set in `settings.xml`)
2. Reads passphrase from server with ID `gpg.passphrase` in `settings.xml`
3. Signs during `verify` phase (before packaging)
4. Creates `.asc` files for each artifact (JAR, POM, sources, javadoc)

**Troubleshooting GPG signing:**

```bash
# Verify key exists locally
gpg --list-secret-keys --keyid-format LONG

# Test signing manually
echo "test" | gpg -u YOUR_KEY_ID --clearsign

# If "Inappropriate ioctl for device" error:
export GPG_TTY=$(tty)
export PINENTRY_USER_DATA="USE_LOOPBACK=1"

# Or add to gpgArguments in plugin config:
<gpgArguments>
  <arg>--pinentry-mode</arg>
  <arg>loopback</arg>
</gpgArguments>
```

### central-publishing-maven-plugin (Publishing)

Located in `pom.xml` (release profile, line 587-599):

```xml
<plugin>
  <groupId>org.sonatype.central</groupId>
  <artifactId>central-publishing-maven-plugin</artifactId>
  <version>0.7.0</version>
  <extensions>true</extensions>
  <configuration>
    <publishingServerId>central</publishingServerId>
    <tokenAuth>true</tokenAuth>
    <autoPublish>true</autoPublish>
    <waitUntil>published</waitUntil>
  </configuration>
</plugin>
```

**Configuration Options:**

| Option | Value | Purpose |
|--------|-------|---------|
| `publishingServerId` | `central` | Server ID in settings.xml with Sonatype token |
| `tokenAuth` | `true` | Use token instead of username/password |
| `autoPublish` | `true` | Auto-publish after validation (requires manual release in old system) |
| `waitUntil` | `published` | Wait for "published" state before returning |

**Sonatype Token Format:**

The token from https://central.sonatype.com/publishing/admin is formatted as:
```
username:password
```

In `settings.xml`, use as:
```xml
<username>username</username>
<password>password</password>
```

### maven-javadoc-plugin (Already Configured)

Located in main `pom.xml` (line 214-243), also active in release profile.

**Key Settings:**
- `failOnError`: true (fail build on javadoc errors)
- `enablePreview`: true (required for Java 26 features)
- `doclint`: all,-missing (strict, but allow missing javadoc tags)
- `executions`: validates during verify, attaches during package

---

## Troubleshooting

### Issue: "401 Unauthorized" from Maven Central

**Symptoms:**
```
[ERROR] Return code is: 401, ReasonPhrase: Unauthorized
```

**Solutions:**

1. **Verify token is set correctly:**
   ```bash
   # Check settings.xml has correct server config
   cat ~/.m2/settings.xml | grep -A 3 "<id>central</id>"

   # Token format should be: username:password (from Central Portal)
   ```

2. **Regenerate token:**
   - Visit https://central.sonatype.com/publishing/admin
   - Go to "User Token"
   - Click "Regenerate" (old token becomes invalid)
   - Update in `settings.xml`

3. **Check for spaces/newlines:**
   ```bash
   # Tokens with trailing spaces cause auth failures
   # Make sure password line has no extra whitespace
   ```

4. **Test auth manually:**
   ```bash
   # Get your token from Central Portal
   export CENTRAL_TOKEN="your_token"

   curl -u "${CENTRAL_TOKEN}:${CENTRAL_TOKEN}" \
     https://central.sonatype.com/api/v1/publisher/status

   # Should return 200 OK
   ```

### Issue: GPG Signing Fails

**Symptoms:**
```
gpg: signing failed: Inappropriate ioctl for device
gpg: [stdin]: clearsign failed: Inappropriate ioctl for device
```

**Solutions:**

1. **Set GPG_TTY:**
   ```bash
   export GPG_TTY=$(tty)
   mvn clean deploy -Prelease
   ```

2. **Use loopback pinentry:**
   ```bash
   export GPG_TTY=$(tty)
   export PINENTRY_USER_DATA="USE_LOOPBACK=1"
   mvn clean deploy -Prelease
   ```

3. **Pass passphrase as property:**
   ```bash
   mvn clean deploy -Prelease \
     -Dgpg.passphrase="your_passphrase"
   ```

4. **Verify key is available:**
   ```bash
   gpg --list-secret-keys --keyid-format LONG
   # Should show your key with ID matching gpg.keyname
   ```

### Issue: Artifacts Not Found After Deployment

**Symptoms:**
- Maven Central returns 404 for new artifact
- `mvn dependency:get` fails even after 30 minutes

**Solutions:**

1. **Check deployment status in Central Portal:**
   - Visit https://central.sonatype.com/publish/
   - Look for your deployment
   - Verify it shows "COMPLETED" state

2. **Check Maven metadata:**
   ```bash
   curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/maven-metadata.xml

   # Should include your version
   <version>2026.1.0</version>
   ```

3. **Wait longer for sync:**
   - Sonatype sync is usually 10-30 minutes
   - Can occasionally take 1-2 hours during maintenance
   - Check status API: https://central.sonatype.com/api/v1/publisher/status

4. **Verify deployment bundle:**
   ```bash
   # Check local target/ directory has all required files
   ls -la target/

   # Must include:
   # - jotp-*.jar (main JAR)
   # - jotp-*-sources.jar (sources)
   # - jotp-*-javadoc.jar (javadoc)
   # - jotp-*.pom (POM file)
   # - *.asc (GPG signatures for each)
   ```

### Issue: "Javadoc Plugin Failed" During Build

**Symptoms:**
```
[ERROR] Error fetching URL: https://docs.oracle.com/en/java/javase/26/docs/api/
[ERROR] doclint errors
```

**Solutions:**

1. **Check internet connectivity:**
   ```bash
   curl -I https://docs.oracle.com/en/java/javase/26/docs/api/
   ```

2. **Use offline mode:**
   ```bash
   mvn clean verify -DofflineLinks=true
   ```

3. **Skip doclint for specific warnings:**
   ```bash
   # In pom.xml javadoc plugin:
   <doclint>all,-missing,-syntax</doclint>
   ```

4. **Fix broken references in code:**
   ```bash
   # Review javadoc errors
   mvn javadoc:javadoc 2>&1 | grep -A 3 error

   # Fix issues in source code
   # Then retry
   ```

### Issue: "Project Version Must Be a Release Version"

**Symptoms:**
```
[ERROR] Project version "2026.1.0-SNAPSHOT" must be a release version
```

**Solutions:**

1. **Remove -SNAPSHOT from version:**
   ```bash
   # In pom.xml, change:
   # <version>2026.1.0-SNAPSHOT</version>
   # to:
   # <version>2026.1.0</version>
   ```

2. **Use semantic versioning:**
   - Releases: `2026.1.0` (no suffix)
   - Development: `2026.1.1-SNAPSHOT`
   - Pre-releases: `2026.1.0-beta`, `2026.1.0-rc.1`

### Issue: "No PGP Signatures Found"

**Symptoms:**
```
[ERROR] GPG signatures are required for release artifacts
```

**Solutions:**

1. **Ensure release profile is active:**
   ```bash
   # Must include -Prelease flag
   mvn clean deploy -Prelease
   # Not: mvn clean deploy
   ```

2. **Verify GPG key configuration:**
   ```bash
   # Check settings.xml
   cat ~/.m2/settings.xml | grep -A 2 gpg.keyname

   # Verify key exists
   gpg --list-secret-keys
   ```

3. **Run with verbose output:**
   ```bash
   mvn clean deploy -Prelease -X | grep -i "gpg\|sign"
   ```

### Issue: Network/Proxy Issues

**Symptoms:**
```
[ERROR] Failed to upload artifact: java.net.ConnectException
```

**Solutions:**

1. **Check proxy settings:**
   ```bash
   # If behind corporate proxy, configure in settings.xml
   cat ~/.m2/settings.xml | grep -A 5 "<proxy>"
   ```

2. **Test connectivity:**
   ```bash
   curl -I https://central.sonatype.com/api/v1/publisher/status
   ```

3. **Disable SSL verification (last resort):**
   ```bash
   # Only for testing, NOT for production
   MAVEN_OPTS="-Dmaven.wagon.http.ssl.insecure=true" \
   mvn clean deploy -Prelease
   ```

---

## Post-Release

### Step 1: Verify in Maven Central

```bash
# Wait for sync (~30 min after "Published" status)

# Test Maven can resolve artifact
mvn dependency:get -Dartifact=io.github.seanchatmangpt:jotp:2026.1.0

# Visit artifact page
# https://central.sonatype.com/artifact/io.github.seanchatmangpt/jotp/2026.1.0

# Check it appears in search
curl https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/2026.1.0/
```

### Step 2: Push Tag to GitHub

```bash
# Push release tag to GitHub
git push origin v2026.1.0

# Verify tag appears on GitHub
# https://github.com/seanchatmangpt/jotp/releases/tag/v2026.1.0
```

### Step 3: Update GitHub Releases Page

```bash
# Create GitHub release (requires gh CLI)
gh release create v2026.1.0 \
  --title "JOTP 2026.1.0" \
  --notes-file RELEASE_NOTES.md

# Or manually at:
# https://github.com/seanchatmangpt/jotp/releases/new
```

**Release Notes Template:**

```markdown
## JOTP 2026.1.0

### New Features
- [List new features]

### Bug Fixes
- [List bug fixes]

### Breaking Changes (if any)
- [List breaking changes]

### Dependencies Updated
- [List updated dependencies]

### Contributors
- [List contributors]

### Maven Central
```xml
<dependency>
  <groupId>io.github.seanchatmangpt</groupId>
  <artifactId>jotp</artifactId>
  <version>2026.1.0</version>
</dependency>
```

### Installation
- [Installation instructions]
```

### Step 4: Update pom.xml for Next Development Version

```bash
# Bump version for next development cycle
nano pom.xml

# Change to next SNAPSHOT version
# <version>2026.2.0-SNAPSHOT</version>

# Commit
git add pom.xml
git commit -m "Bump version to 2026.2.0-SNAPSHOT"
git push origin main
```

### Step 5: Announce Release

- Post on GitHub Discussions
- Tweet/social media announcement
- Update documentation with new version
- Add to changelog/website

---

## Best Practices

### Before Releasing

1. **Run full test suite:**
   ```bash
   mvn clean verify
   ```

2. **Check for security vulnerabilities:**
   ```bash
   mvn versions:display-dependency-updates
   # Review and update if needed
   ```

3. **Format code:**
   ```bash
   mvn spotless:apply
   ```

4. **Update documentation:**
   - RELEASE_NOTES.md
   - README.md (version examples)
   - CHANGELOG.md (git history)

5. **Tag properly:**
   - Use annotated tags: `git tag -a v2026.1.0 -m "Release 2026.1.0"`
   - Prefix with `v`
   - Match pom.xml version exactly

### During Release

1. **Use release profile:**
   ```bash
   mvn clean deploy -Prelease  # Always include -Prelease
   ```

2. **Save deployment logs:**
   ```bash
   mvn clean deploy -Prelease 2>&1 | tee deploy-2026.1.0.log
   ```

3. **Monitor deployment status:**
   - Watch Central Portal dashboard
   - Don't assume it worked just because Maven succeeded

4. **Never reuse versions:**
   - Once deployed, a version is permanent
   - Cannot be re-released or deleted easily
   - Use new version numbers for corrections

### After Release

1. **Verify artifacts exist:**
   ```bash
   mvn dependency:get -Dartifact=io.github.seanchatmangpt:jotp:2026.1.0
   ```

2. **Test in downstream project:**
   ```xml
   <dependency>
     <groupId>io.github.seanchatmangpt</groupId>
     <artifactId>jotp</artifactId>
     <version>2026.1.0</version>
   </dependency>
   ```

3. **Monitor for issues:**
   - Watch GitHub issues for release-specific bugs
   - Monitor download stats on Maven Central

---

## Reverting a Release (Emergency Only)

If a critical bug is discovered immediately after release:

### Option 1: Yank the Release (Preferred)

Maven Central allows "yanking" (marking as unavailable):

```bash
# Via Central Portal:
# 1. Go to your artifact page
# 2. Click "Yank release"
# 3. Confirm removal

# Creates a new release immediately with bugfix
git tag -a v2026.1.1 -m "Bugfix release"
mvn clean deploy -Prelease
```

### Option 2: Delete from Maven Central (If Yanking Not Available)

Contact Sonatype support at: https://issues.sonatype.org

Include:
- Artifact coordinates: `io.github.seanchatmangpt:jotp:2026.1.0`
- Reason for deletion
- Request urgency

---

## References

### Official Documentation

- [Sonatype Central Publishing Guide](https://central.sonatype.org/publish/)
- [central-publishing-maven-plugin Docs](https://github.com/sonatype/central-publishing-maven-plugin)
- [Maven POM Reference](https://maven.apache.org/pom.html)
- [GPG Official Documentation](https://gnupg.org/documentation/)
- [Maven GPG Plugin Docs](https://maven.apache.org/plugins/maven-gpg-plugin/)

### JOTP Project Documentation

- [JOTP README](../README.md)
- [RELEASE.md](./archive/release/RELEASE.md) - Legacy release guide
- [RELEASE_NOTES.md](./archive/release/RELEASE_NOTES.md) - Template for release notes
- [CLAUDE.md](../CLAUDE.md) - Project conventions and guidelines

### External Tools

- [Sonatype Central Portal](https://central.sonatype.com)
- [GitHub CLI](https://cli.github.com/) - for `gh release create`
- [Maven Daemon (mvnd)](https://maven.apache.org/mvnd/) - faster builds
- [PGP Keyservers](https://pgp.mit.edu) - publish GPG keys

### Related Make Targets

```bash
# From Makefile
make gpg-check              # Verify GPG key is configured
make deploy-snapshot        # Deploy snapshot version
make deploy-release         # Deploy release to Maven Central
make release-prepare        # Run maven-release-plugin prepare phase
make release-perform        # Run maven-release-plugin perform phase
```

---

## Changelog

### Version 1.0 (2026-03-17)

- Initial guide created
- Covers Maven 4 + central-publishing-maven-plugin
- Includes settings.xml templates
- Comprehensive troubleshooting section
- Best practices and post-release checklist

---

## Questions?

For issues with:
- **JOTP code**: Open GitHub Issue at https://github.com/seanchatmangpt/jotp/issues
- **Maven Central**: Check [Central Support](https://central.sonatype.org/support/)
- **GPG/Signing**: See [GPG Manual](https://gnupg.org/documentation/)
- **Release scripts**: Check `scripts/release/README.md`
