# VHS demo for maven-sane-out
# Run: vhs demo.tape

Output demo.gif

Set Shell bash
Set FontSize 14
Set Width 1100
Set Height 620
Set Padding 15
Set Theme "Catppuccin Mocha"
Set TypingSpeed 40ms

Type "# Standard Maven — errors buried in noisy output"
Enter
Sleep 1s

Type "mvn -B -f test-project compile 2>/dev/null"
Enter
Sleep 8s

Enter
Sleep 1s

# Silently configure the agent
Hide
Type "export MAVEN_OPTS='-Xshare:off -javaagent:target/maven-sane-out-slf4j-1.0-SNAPSHOT.jar'"
Enter
Sleep 200ms
Show

Type "# With maven-sane-out quiet mode — just the errors"
Enter
Sleep 1s

Type "mvn -Dsane.quiet=5 -B -f test-project compile 1>/dev/null"
Enter
Sleep 8s

Sleep 2s
