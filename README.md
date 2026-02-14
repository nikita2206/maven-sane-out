# maven-sane-out

Routes Maven `[ERROR]` lines to stderr. Everything else stays on stdout.

![demo](demo.gif)

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

Via environment variables or system properties (`-D` flags). System properties take precedence.

| Environment Variable | System Property | Effect |
|---|---|---|
| `MAVEN_SANE_OUT_DISABLE=1` | `-Dsane.disable` | Bypass the agent entirely |
| `MAVEN_SANE_OUT_WARNINGS=1` | `-Dsane.warnings` | Also route `[WARNING]` to stderr |
| `MAVEN_SANE_OUT_EXCLUDE=pat1;pat2` | `-Dsane.exclude=pat1;pat2` | Lines matching any pattern stay on stdout even if ERROR/WARNING |
| `MAVEN_SANE_OUT_QUIET=N` | `-Dsane.quiet=N` | Quiet mode: suppress all non-error output, show N context lines before each error (per-thread) |

### Examples

```bash
# Route both errors and warnings to stderr
MAVEN_SANE_OUT_WARNINGS=1 mvn compile

# Keep "bootstrap class path" warnings on stdout
MAVEN_SANE_OUT_WARNINGS=1 MAVEN_SANE_OUT_EXCLUDE="bootstrap class path;obsolete" mvn compile

# Capture just errors
mvn compile 2>errors.txt 1>/dev/null

# Quiet mode: only errors on stderr, with 10 lines of context
MAVEN_SANE_OUT_QUIET=10 mvn compile
# or equivalently:
mvn -Dsane.quiet=10 compile

# Quiet mode: errors only, no context
MAVEN_SANE_OUT_QUIET=0 mvn compile
```

## How it works

A Java agent that instruments SLF4J's `SimpleLogger.write()` method using ASM bytecode transformation. At the SLF4J level, each log call (including multiline messages and stack traces) is routed as a single unit to the correct stream based on its level. Works with both SLF4J 1.x and 2.x.

## Why ASM bytecode transformation?

A custom SLF4J provider (replacing SimpleLogger entirely) would avoid the ASM dependency but has significant downsides:

- **Maven ships its own SLF4J provider** (`maven-slf4j-provider`) with custom formatting, ANSI colors, and features like `--fail-on-severity`. A replacement provider must reimplement all of this or lose it.
- **`SimpleLogger.write()` is package-private**, so it can't be overridden by subclassing. There's no protected hook between message formatting and stream selection — you'd have to reimplement the full logging path.
- **Classloader boundaries** prevent same-package tricks: the agent runs on the bootstrap classloader while SimpleLogger is loaded by Maven's classloader, making them different runtime packages.
- **Two SLF4J versions** to support: Maven 3.x uses SLF4J 1.x (StaticLoggerBinder), Maven 4.x uses SLF4J 2.x (ServiceLoader). Each needs a different provider mechanism.

The ASM approach sidesteps all of this — it rewrites one method in the existing logger, preserving Maven's formatting, colors, and all other behavior. ASM is shaded into the JAR, invisible to users, and adds ~125KB.
