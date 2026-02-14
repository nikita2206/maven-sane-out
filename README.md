# maven-sane-out

Routes Maven `[ERROR]` lines to stderr. Everything else stays on stdout.

```bash
# errors on stdout (default Maven behavior)
mvn compile 2>/dev/null
[ERROR] Failed to execute goal...  # still visible

# errors on stderr (with maven-sane-out)
mvn compile 2>/dev/null
                                   # errors gone — they went to stderr
```

## Install

Download the latest release:

```bash
curl -sL https://github.com/nikita2206/maven-sane-out/releases/latest/download/maven-sane-out.jar \
  -o ~/.mvn/maven-sane-out.jar
```

Add to `~/.mavenrc`:

```bash
MAVEN_OPTS="$MAVEN_OPTS -javaagent:$HOME/.mvn/maven-sane-out.jar"
```

Done. All `mvn` invocations now route `[ERROR]` to stderr.

## Configuration

All via environment variables:

| Variable | Effect |
|---|---|
| `MAVEN_SANE_OUT_DISABLE=1` | Bypass the agent entirely |
| `MAVEN_SANE_OUT_WARNINGS=1` | Also route `[WARNING]` to stderr |
| `MAVEN_SANE_OUT_EXCLUDE=pat1;pat2` | Lines matching any pattern stay on stdout even if ERROR/WARNING |

### Examples

```bash
# Route both errors and warnings to stderr
MAVEN_SANE_OUT_WARNINGS=1 mvn compile

# Keep "bootstrap class path" warnings on stdout
MAVEN_SANE_OUT_WARNINGS=1 MAVEN_SANE_OUT_EXCLUDE="bootstrap class path;obsolete" mvn compile

# Capture just errors
mvn compile 2>errors.txt 1>/dev/null
```

## How it works

A Java agent that instruments SLF4J's `SimpleLogger.write()` method using ASM bytecode transformation. At the SLF4J level, each log call (including multiline messages and stack traces) is routed as a single unit to the correct stream based on its level. Works with both SLF4J 1.x and 2.x.
