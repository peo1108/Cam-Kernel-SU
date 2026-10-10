# Host tests for kernel logic Cam wrote itself (not part of the module build).
# tests/stub fakes the kernel API; quote includes look in stub before kernel/.
CC ?= cc
CFLAGS += -std=gnu11 -g -O1 -Wall -Wextra -Wno-unused-parameter -fsanitize=address,undefined -fno-omit-frame-pointer
INCLUDES = -I stub -iquote stub -iquote ..

.PHONY: test clean
test: pkg_tracker_test
	./pkg_tracker_test

pkg_tracker_test: pkg_tracker_test.c ../policy/pkg_tracker.c $(wildcard stub/*.h stub/*/*.h)
	$(CC) $(CFLAGS) $(INCLUDES) -o $@ pkg_tracker_test.c

clean:
	rm -f pkg_tracker_test
