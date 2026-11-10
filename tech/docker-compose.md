# Docker Compose and YAML (the optional `compose.yaml`)

## What it is

**Docker** runs programs in **containers**. A container is a sealed, lightweight box that holds one program together with the exact files it needs, so it runs the same everywhere. A container is created from an **image**, a read-only package; images are stored in a **registry** such as Docker Hub and are named `name:tag`, where the tag picks a version.

**Docker Compose** reads a text file that describes one or more containers and starts them with one command, `docker compose up`. The file is written in **YAML**, a format built from indentation. Two spaces under a key mean "this belongs to that key". There are three building blocks: `key: value` pairs, **lists** (lines that start with `- `) and **comments** (`#` to the end of the line). Tabs are not allowed for indentation.

**This path was not run on the verification machine.** Docker was not running there, so everything below comes from reading the file and from the README, not from running a container.

## Why this repository uses it

It gives a learner who has no JDK a way to run the full build: a clean container with JDK 25 runs `./gradlew --no-daemon check` on the mounted repository. The README marks it "optional" and "[not run]".

## Where it appears

| Path | What it does |
| --- | --- |
| `compose.yaml` | The only Compose file: one service, one named volume. |
| `gradlew` | The command the container runs (`posix-shell.md`). |
| `.gitignore` | Keeps the `build/` and `.gradle/` folders that the container writes out of Git (`git.md`). |

## How it works underneath

1. `docker compose up` reads `compose.yaml` in the current folder.
2. For the service `jdk` it pulls the image `eclipse-temurin:25.0.5_8-jdk-noble` from the registry if it is not on the machine yet.
3. It creates a container, mounts the volumes into it, sets the working folder and starts the **command**.
4. The container's program prints to your terminal. When the command ends, the container stops, and Compose exits.
5. A **bind mount** shows a folder from your machine inside the container (changes go both ways). A **named volume** is storage that Docker manages and keeps between runs, so the Gradle cache survives.
6. Inside the container the first `./gradlew` downloads Gradle 9.9.1 and the libraries, as on any machine (`gradle-wrapper.md`). With the named volume the second run reuses them.

## Walkthrough of this repository's use

```yaml
# Optional: runs the full build in a clean JDK 25 container, so no local JDK is needed.
# The image tag was checked on Docker Hub on 10 November 2026.
services:
  jdk:
    image: eclipse-temurin:25.0.5_8-jdk-noble
    working_dir: /workspace
    volumes:
      - .:/workspace
      - gradle-cache:/root/.gradle
    command: ./gradlew --no-daemon check

volumes:
  gradle-cache:
```

- Lines 1 and 2 are comments. They say it is optional and that the image tag was checked on Docker Hub on 10 November 2026.
- `services:` starts the list of containers. Everything indented below it is a service.
- `jdk:` is the name of the one service. The name is yours; it labels the container.
- `image: eclipse-temurin:25.0.5_8-jdk-noble` is the image. `eclipse-temurin` is the name, the tag after the colon says: JDK (not just a runtime) 25.0.5 build 8, on the Ubuntu release named "noble". Note that this is JDK 25.0.5, newer than the 25.0.2 on the verification machine.
- `working_dir: /workspace` is the folder the command starts in, inside the container.
- `volumes:` under the service is a list of things to mount. Each item is `source:target`.
- `- .:/workspace` is a bind mount: `.` means the folder containing `compose.yaml` (the repository), shown inside the container as `/workspace`. The build therefore sees your files, and its `build/` output appears in your repository folder.
- `- gradle-cache:/root/.gradle` mounts the named volume `gradle-cache` at `/root/.gradle`, the Gradle settings and cache folder of the user `root` (containers run as root by default). Because it is a volume, the downloads survive between runs.
- `command: ./gradlew --no-daemon check` is what the container runs. `./gradlew` is the wrapper, `check` is the full gate, and `--no-daemon` makes Gradle run without leaving a background process, which would be pointless in a container that stops right after.
- The last block, `volumes:` at the top level with no indentation, declares the named volume. `gradle-cache:` has nothing after the colon: in YAML that is an empty value (`null`), which here means "create it with default settings". I confirmed this with Ruby's YAML reader: it reads the file as `{"services"=>{"jdk"=>{"image"=>"eclipse-temurin:25.0.5_8-jdk-noble", "working_dir"=>"/workspace", "volumes"=>[".:/workspace", "gradle-cache:/root/.gradle"], "command"=>"./gradlew --no-daemon check"}}, "volumes"=>{"gradle-cache"=>nil}}`.

### Things to know when it is run

- **File ownership on Linux.** The container writes as root, so files in `build/` may belong to root on a Linux host. On Docker Desktop for Mac this does not show.
- **Different JDK.** 25.0.5 here against 25.0.2 for the results in the README.
- **Not tested.** README: "[not run] Docker was not running on the verification machine, so this path was not tested."
- It needs no ports, because the build is not a server.
- **Do not benchmark in it.** The README warns: do not run the benchmark inside the container to describe your machine, because a container adds its own scheduler limits. The container runs `check` only, which uses tiny settings and asserts no speed. The `benchmark`, `sizing`, `eventLoop`, `threadCost` and `pinning` tasks are meant for your own machine (`gradle.md`).

## Try it yourself

Docker was not run for these notes, so none of these commands uses it.

1. Read the file with line numbers:

```
cat -n compose.yaml
```

   Expected: the 13 lines quoted above.

2. Check that the YAML is valid and see its structure (macOS has Ruby):

```
ruby -ryaml -e 'p YAML.load_file("compose.yaml")'
```

   Expected: the one-line structure shown above, ending `"volumes"=>{"gradle-cache"=>nil}}`.

3. Confirm there are no tab characters (YAML forbids tabs for indentation):

```
grep -c "$(printf '\t')" compose.yaml
```

   Expected: `0` (and a non-zero exit status, since there were no matches).

4. When Docker is available, the README's command is `docker compose up`. I did not run it, so I cannot give its output.

## Mistakes people make

- **Tabs for indentation.** YAML rejects them. Use spaces (`.editorconfig` says two).
- **Wrong indentation.** A key indented one space too far or too little changes its meaning or breaks the file.
- **Expecting `.` in a bind mount to be the shell's current folder.** In Compose, relative paths are relative to the Compose file.
- **Forgetting the named volume is stored by Docker, not in the repository.** Delete it with `docker compose down -v`.
- **Running with a different JDK than the notes.** The tag decides the JDK version.
- **Using the container for fast edit-and-run.** Each start is a clean environment; the local `./gradlew` is faster.

## Check yourself

1. *What is the difference between an image and a container?* An image is the read-only package; a container is a running instance made from it.
2. *What does `- .:/workspace` do?* It shows the repository folder inside the container at `/workspace`.
3. *Why is there a `gradle-cache` volume?* So downloaded Gradle files survive between runs and the second build is fast.
4. *What does `--no-daemon` do?* It runs Gradle without a background process, useful in a short-lived container.
5. *Was this path tested?* No. Docker was not running on the verification machine.

## Further reading

- Compose file reference: <https://docs.docker.com/reference/compose-file/>
- `docker compose up`: <https://docs.docker.com/reference/cli/docker/compose/up/>
- YAML 1.2.2 specification: <https://yaml.org/spec/1.2.2/>
