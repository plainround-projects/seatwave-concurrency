# The CSV and SVG files the benchmark writes

## What it is

Two plain-text file formats.

**CSV** means "comma-separated values". A CSV file holds a table as text. Each line is one **row**. The cells of a row are separated by commas. The first row names the columns. Any spreadsheet program opens a CSV file as a table, and any program can read it as text. `BenchmarkCsv` writes one CSV file for each benchmark run, with one row for each step of each thread model.

**SVG** means "Scalable Vector Graphics". An SVG file is a drawing written as text. It is a list of **tags** such as `<line>`, `<circle>` and `<text>`, each with **attributes** such as `x1="80"` that say where and how to draw. A web browser reads the tags and draws the picture. Because it is text, it needs no charting library and no image program to make one: `LoadChartSvg` builds the file by joining strings. Because it is made of shapes and not pixels, it stays sharp at any size.

## Why this repository uses it

Module 4 claims that latency stays flat as load rises and then jumps when a pool nears full use. A table of numbers shows it to a person who reads every digit. A chart shows it at a glance. The benchmark writes both: the CSV so you can load the numbers into a spreadsheet and chart them yourself, and two SVG charts so the shape is visible at once. The program draws the charts itself, with no library, so the repository has no charting dependency to pin and to check.

## Where it appears

| File | What it does |
|---|---|
| `src/main/java/io/plainround/seatwave/concurrency/bench/BenchmarkCsv.java` | Builds the CSV text: the header line and one line for each step. |
| `src/main/java/io/plainround/seatwave/concurrency/bench/LoadChartSvg.java` | Builds the SVG text of one chart. |
| `src/main/java/io/plainround/seatwave/concurrency/bench/ChartMetric.java` | Says which value a chart plots: p99 or error percent. |
| `src/main/java/io/plainround/seatwave/concurrency/bench/BenchmarkRun.java` | `writeFiles` writes the three files to the output folder. |
| `build/bench/<name>.csv`, `<name>-p99.svg`, `<name>-errors.svg` | The files, after a run. `build/` is ignored by Git. |

## How it works underneath

`BenchmarkCli` runs the benchmark, then `BenchmarkRun.writeFiles` makes the output folder (`Files.createDirectories`), builds the three texts in memory, and writes each with `Files.writeString(path, text, StandardCharsets.UTF_8)`. The name of the files is the name of the settings file without `.properties`: `config/unbounded.properties` gives `unbounded.csv`, `unbounded-p99.svg` and `unbounded-errors.svg`. Nothing is sent anywhere. Each line of the files is built with `String.format(Locale.ROOT, ...)`, so a decimal point is always a dot and there are no thousands separators in the CSV, whatever the language of your machine.

I ran the shortest benchmark, `./gradlew -q benchmark --args='config/unbounded.properties build/bench'`, and copied the three files out of `build/` to read them. Their sizes were 427 bytes (CSV), 1,741 bytes (p99 chart) and 1,588 bytes (error chart). The numbers differ on every run; the shape does not.

## Walkthrough of this repository's use

### The CSV file

The first line is the header, with twenty column names. A real run wrote these three lines:

```text

mode,offered_rps,achieved_rps,sent,created_201,taken_409,refused_queue_503,refused_pool_503,other_503,client_timeouts,error_pct,p50_ms,p99_ms,pool_wait_p99_ms,threads_alive_max,platform_threads_max,queue_max,pool_waiters_max,pool_busy_pct,cpu_pct
platform,440,438.1,4400,3698,683,0,0,0,19,0.43,982.6,1933.5,384.9,64,71,662,49,99.5,1.1
platform,600,185.9,6000,1332,527,0,0,0,4141,69.02,961.5,1969.9,386.7,64,71,2233,50,99.6,0.7
```


Viewed as a table (I used `column -s, -t unbounded.csv`), the same data lines up:

| Column | Step 1 (440 holds/s) | Step 2 (600 holds/s) | Meaning |
|---|---|---|---|
| `mode` | platform | platform | The thread model, in lower case. |
| `offered_rps` | 440 | 600 | The rate the generator aimed for, holds per second. |
| `achieved_rps` | 438.1 | 185.9 | Good replies (201 and 409) per second of the step. |
| `sent` | 4400 | 6000 | Holds sent: rate x step length (440 x 10 s). |
| `created_201` | 3698 | 1332 | Replies 201: the seat is held. |
| `taken_409` | 683 | 527 | Replies 409: another fan has the seat. |
| `refused_queue_503` | 0 | 0 | 503 from the door or the queue wait limit. |
| `refused_pool_503` | 0 | 0 | 503 from the borrow limit. |
| `other_503` | 0 | 0 | 503 from the database or an interrupt. |
| `client_timeouts` | 19 | 4141 | Replies later than the client's 2-second patience, or never. |
| `error_pct` | 0.43 | 69.02 | Holds without a good reply in time, in percent. |
| `p50_ms` | 982.6 | 961.5 | Median latency of good replies, milliseconds. |
| `p99_ms` | 1933.5 | 1969.9 | The 99th percentile latency of good replies. |
| `pool_wait_p99_ms` | 384.9 | 386.7 | p99 of the wait for a connection. |
| `threads_alive_max` | 64 | 64 | Most threads that existed for SeatWave work. |
| `platform_threads_max` | 71 | 71 | Most platform threads in the whole JVM. |
| `queue_max` | 662 | 2233 | Longest executor queue at one reading. |
| `pool_waiters_max` | 49 | 50 | Most threads waiting for a connection. |
| `pool_busy_pct` | 99.5 | 99.6 | The pool's average utilization. |
| `cpu_pct` | 1.1 | 0.7 | Process CPU as a share of every core. |

Check the arithmetic. 3698 + 683 + 0 + 0 + 0 + 19 = 4400, which is `sent`: every hold ends in exactly one bucket (`StepResult` refuses a result where the buckets do not add up). `error_pct` is (4400 - 3698 - 683) x 100 / 4400 = 19 x 100 / 4400 = 0.43. In step 2, 6000 - 1332 - 527 = 4141 holds had no good reply, 69.02 percent.

Read the story the numbers tell. Both steps offer more than the 16 connections can serve (400 holds per second), and the limits are opened wide, so `refused_queue_503` and `refused_pool_503` stay 0 and the queue grows (`queue_max` 2233). Holds wait instead of being refused, p99 reaches about two seconds (the client's patience), and 69 percent of the holds in step 2 time out on the client. That is what an unbounded queue does.

**How to open the CSV.** Double-click `build/bench/unbounded.csv` and a spreadsheet program opens it as a table. Or use any text viewer. In a terminal, `cat build/bench/unbounded.csv` shows the raw lines and `column -s, -t build/bench/unbounded.csv` shows an aligned table. To make your own chart in a spreadsheet, select `offered_rps` and `p99_ms` and insert a line chart.

### The SVG chart

`unbounded-p99.svg` is this text (27 lines). I print the lines that carry the drawing, with the repetitive axis numbers left out in the middle:

```xml

<svg xmlns="http://www.w3.org/2000/svg" width="720" height="420" viewBox="0 0 720 420" font-family="sans-serif" font-size="12">
<rect width="100%" height="100%" fill="#ffffff"/>
<text x="360.0" y="25.0" text-anchor="middle">unbounded: hold p99 against load</text>
<line x1="80" y1="360" x2="560" y2="360" stroke="#000000"/>
<line x1="80" y1="50" x2="80" y2="360" stroke="#000000"/>
...
<line x1="80" y1="313.5" x2="560" y2="313.5" stroke="#555555" stroke-dasharray="6 4"/>
<text x="564.0" y="317.5" text-anchor="start">target 300</text>
<circle cx="291.2" cy="60.3" r="4" fill="#d55e00"/>
<circle cx="368.0" cy="54.7" r="4" fill="#d55e00"/>
<polyline fill="none" stroke="#d55e00" stroke-width="2" points="291.2,60.3 368.0,54.7"/>
<rect x="570" y="68.0" width="14" height="4" fill="#d55e00"/>
<text x="590.0" y="72.0" text-anchor="start">platform threads</text>
</svg>
```


Line by line:

- `<svg xmlns="http://www.w3.org/2000/svg" width="720" height="420" viewBox="0 0 720 420" font-family="sans-serif" font-size="12">` is the opening tag. `xmlns` is a **namespace**: it tells the reader that the tags are SVG. `width` and `height` are the size. `viewBox="0 0 720 420"` is the drawing's own coordinate system: x from 0 to 720, y from 0 to 420. The last two attributes are inherited by every text.
- `<rect width="100%" height="100%" fill="#ffffff"/>` draws a white rectangle over the whole chart, so it reads well on a dark page. `fill` is a color in hex: `#ffffff` is white.
- `<text x="360.0" y="25.0" text-anchor="middle">...</text>` puts the title at x = 360 (the middle) and y = 25 (near the top). `text-anchor="middle"` centers the text on x.
- `<line x1="80" y1="360" x2="560" y2="360" stroke="#000000"/>` is the horizontal axis: from (80, 360) to (560, 360). The second `<line>` is the vertical axis, from (80, 50) to (80, 360). Remember that in SVG **y counts downward** from the top, so 360 is near the bottom.
- The `<text>` lines with numbers such as `200`, `400` and `1,000` are the axis labels. The tick labels are rounded up to round numbers, so the horizontal axis runs to 1,000 although the highest rate offered was 600.
- `<line ... stroke="#555555" stroke-dasharray="6 4"/>` is the dashed target line (6 pixels drawn, 4 skipped) and `target 300` labels it, at height 313.5. It is drawn only on the p99 chart.
- `<circle cx="291.2" cy="60.3" r="4" fill="#d55e00"/>` is one data point: center (291.2, 60.3), radius 4, in vermilion orange. Check it by hand: the x for a rate of 440 is 80 + (720 - 80 - 160) x 440 / 1000 = 80 + 480 x 0.44 = 291.2. The y for a p99 of 1933.5 on an axis that ends at 2,000 is 420 - 60 - (420 - 50 - 60) x 1933.5 / 2000 = 360 - 310 x 0.96675 = 60.3. A high p99 is a small y: near the top.
- `<polyline fill="none" stroke="#d55e00" stroke-width="2" points="291.2,60.3 368.0,54.7"/>` joins the points of one thread model with a line. `fill="none"` means no area is filled.
- `<rect x="570" y="68.0" width="14" height="4" fill="#d55e00"/>` is the colored swatch of the legend and `<text ...>platform threads</text>` is its label. Platform threads are orange, virtual threads blue (`#0072b2`), two colors chosen to stay apart for the common kinds of color blindness.
- `</svg>` closes the document.

`unbounded-errors.svg` has the same structure, with the title `unbounded: holds without a good reply`, the vertical axis label `holds without a good reply in time (%)` and the two points at y = 358.7 (0.43 percent) and y = 146.0 (69.02 percent). It has no dashed target line.

I checked both files with Python's XML parser: they are well-formed XML, each with the root tag `svg` and two circles.

**How to open the SVG.** Open it in a web browser: drag the file into a browser window, or on a Mac run `open build/bench/unbounded-p99.svg`, which opens it in the default program. You can also open it in a text editor to read the tags. Because it is text, `git diff` shows exactly what changed between two runs.

## Try it yourself

Run these from the repository folder.

**1. Make the files.**

```text
./gradlew -q benchmark --args='config/unbounded.properties build/bench'
```

It took 33 seconds on my machine. The last line printed was:

```text
Wrote build/bench/unbounded.csv, build/bench/unbounded-p99.svg, build/bench/unbounded-errors.svg
```

**2. Look at the CSV.** `cat build/bench/unbounded.csv` prints the header and two data lines like the ones above. The digits will differ from mine. `column -s, -t build/bench/unbounded.csv` prints the aligned table.

**3. Check the sum.** In any data line, the fields `created_201`, `taken_409`, `refused_queue_503`, `refused_pool_503`, `other_503` and `client_timeouts` add up to `sent`. I checked it on both lines of my run.

**4. Draw it.** Open `build/bench/unbounded-p99.svg` in a browser. You see one orange line with two points and the dashed target at 300. Change `load.steps.rps` in a copy of the settings file to add steps, run again, and watch the line grow.

**5. Tidy up.** `rm -rf build/bench` removes the files. They are ignored by Git, so nothing else is needed.

## Mistakes people make

- **Opening a CSV in a spreadsheet that guesses the number format.** A spreadsheet set to a language that uses a comma as the decimal point may read `438.1` as text. The benchmark writes dots on purpose; import the file as "comma separated" with a dot decimal.
- **A thousands separator inside a CSV number.** It would break the column count. The CSV has none; the SVG axis labels (text, not data) do have them (`1,000`).
- **Believing the picture from one run.** One run per mode, on a machine that is doing other work. Repeat runs and read the spread.
- **Reading y the wrong way round in SVG.** y grows downward. A tall bar is a small y.
- **A chart axis that starts at a surprise.** Both axes start at 0 here, and the maximum is rounded up to 1, 2 or 5 times a power of ten (`niceCeiling`).
- **Putting `<` or `&` in a title.** It would break the XML. `LoadChartSvg.escape` replaces them.
- **Committing the output.** `build/` is in `.gitignore`.

## Check yourself

1. **What do the six counts `created_201` to `client_timeouts` add up to?** The number of holds `sent`.
2. **Why is the y of a high p99 a small number in the SVG?** SVG counts y downward from the top of the picture.
3. **How is the x of a point worked out?** `80 + 480 x rate / axisMax`: the left margin plus the share of the plot width. For 440 on an axis to 1,000 it is 291.2.
4. **What does `stroke-dasharray="6 4"` do?** It draws a line as 6 pixels on, 4 pixels off, repeating.
5. **Why does the program write the charts itself?** So the repository needs no charting library: an SVG file is text, and a few string formats are enough.

## Further reading

Official documentation only:

- The SVG specification and its element reference: https://www.w3.org/TR/SVG2/
- The CSV format, RFC 4180 (the common habit that most programs follow): https://www.rfc-editor.org/rfc/rfc4180
- `java.nio.file.Files.writeString` in the Java SE 25 API documentation: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html
