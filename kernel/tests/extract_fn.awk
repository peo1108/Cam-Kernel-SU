# Prints the definitions (not the prototypes) of the static functions listed in `names`
# (space separated) from a kernel C file, so a host test builds the module's own code
# instead of a copy that can drift. Fails when one is missing (renamed or moved).
# Usage: awk -v names="foo bar" -f extract_fn.awk file.c > out.inc
BEGIN {
    n = split(names, want, " ")
}
!grab {
    for (i = 1; i <= n; i++) {
        if ($0 ~ ("^static .*[ *]" want[i] "\\(")) {
            grab = want[i]
            buf = ""
            body = 0
            break
        }
    }
}
grab {
    buf = buf $0 "\n"
    if ($0 ~ /^\{/)
        body = 1
    if (!body && $0 ~ /\);[ \t\r]*$/) {
        grab = "" # a prototype
        next
    }
    if (body && $0 ~ /^\}/) {
        printf "%s\n", buf
        found[grab] = 1
        grab = ""
    }
}
END {
    for (i = 1; i <= n; i++) {
        if (!(want[i] in found)) {
            print "extract_fn.awk: no definition of " want[i] > "/dev/stderr"
            exit 1
        }
    }
}
