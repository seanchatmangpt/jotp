build:
    make compile

test:
    make test

test-full:
    make verify

build-release:
    make package

polish:
    make format

clean:
    make clean

bench:
    make benchmark-quick

ci: polish test-full
