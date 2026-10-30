# Host tests for kernel logic Cam wrote itself (not part of the module build).
# tests/stub fakes the kernel API; quote includes look in stub before kernel/.
CC ?= cc
CFLAGS += -std=gnu11 -g -O1 -Wall -Wextra -Wno-unused-parameter -fsanitize=address,undefined -fno-omit-frame-pointer
INCLUDES = -I stub -iquote stub -iquote ..

.PHONY: test clean
test: pkg_tracker_test selinux_hide_test
	./pkg_tracker_test
	./selinux_hide_test

pkg_tracker_test: pkg_tracker_test.c xml_builder.h ../policy/pkg_tracker.c ../policy/pkg_xml.c ../policy/pkg_xml.h \
		$(wildcard stub/*.h stub/*/*.h)
	$(CC) $(CFLAGS) $(INCLUDES) -o $@ pkg_tracker_test.c

# The parsing functions come out of the module source itself, not a copy.
selinux_hide_parse.inc: ../feature/selinux_hide.c extract_fn.awk
	awk -v names="string_to_context_struct security_context_to_sid_with_policy" -f extract_fn.awk $< > $@ || \
		{ rm -f $@; exit 1; }

selinux_hide_test: selinux_hide_test.c selinux_hide_parse.inc
	$(CC) $(CFLAGS) -o $@ selinux_hide_test.c

clean:
	rm -f pkg_tracker_test selinux_hide_test selinux_hide_parse.inc
