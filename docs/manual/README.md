# The Operator's Manual and the Rules of Engagement

Sources for the adapter's two documents:

| Document | For | Source | Output |
| --- | --- | --- | --- |
| Operator's Manual | Whoever installs, configures and runs the adapter | `operator/src/` | [`../OperatorManual.pdf`](../OperatorManual.pdf) |
| Rules of Engagement | A participant's FIX developers and support | `roe/src/` | [`../RulesOfEngagement.pdf`](../RulesOfEngagement.pdf) |

```sh
npm install
npm run build          # both PDFs
npm run operator       # one; or `npm run roe`
npm run html           # stops at build/<doc>.html, the fast loop for stylesheet work
```

The toolchain is most-exchange's (`../most-exchange/docs/manual`), so the two projects' documents
read as one set: Markdown rendered to one paginated HTML document by markdown-it, printed to A4 by
headless Chrome, with most-exchange's `manual.css`. `CHROME_PATH` overrides the browser if it is not
in a standard location.

## Layout

```
operator/src/01-…md … 07-index.md    the Operator's Manual's chapters, in file-name order
operator/cover.html                  its cover; {{DATE}} is substituted at build time
roe/src/01-…md … 08-index.md         the Rules of Engagement's chapters
roe/cover.html                       its cover
assets/fig-*.svg                     hand-drawn diagrams, shared
manual.css                           most-exchange's print stylesheet
build.mjs                            Markdown → HTML → PDF, for either document
```

## Markup beyond Markdown

As most-exchange's manual:

- **Callouts:** `::: term Title`, `::: note`, `::: warning`, `::: todo` … `:::`. `term` defines
  vocabulary where it is first needed, `note` and `warning` qualify the text around them, `todo`
  marks something designed but not built.
- **Figures:** a paragraph that is only an image becomes a numbered figure, the alt text its caption.
  Chapters refer to `../../assets/fig-….svg`, so the Markdown reads correctly on its own.
- **Page breaks:** `<!-- pagebreak -->`. Chapters always start on a new page.

## Conventions the text follows

- **Section headings carry their own number** (`## 4.7 The sessions file`). The number is the
  cross-reference target throughout, the indexes included: renumbering a section means fixing its
  references, in both documents (they cite each other's sections only by name).
- **Transcripts and FIX messages are real output** from the development stack (`deploy/`),
  regenerated rather than edited. A log line shown only to give its shape is written as a format,
  with `<placeholders>`, never with made-up values.
- **The Rules of Engagement says only what the participant sees.** Internal mechanisms (the archive,
  the store, Artio) belong in the Operator's Manual. Both follow `docs/Design.md`; when behaviour
  changes there, change both documents in the same commit.
- **No latency figure without `perf-claim`.** The manual points to `docs/Measurements.md` instead.
- **Not built is marked so**: a `todo` callout where the reader would expect it to work, and the
  consolidated list in the Operator's Manual, 6.9.

## Refreshing the transcripts

From the development stack (Operator's Manual, section 2):

```sh
./gradlew :adapter:installDist :tools:installDist
(cd ../most-exchange/deploy && docker compose up -d)
docker compose -f deploy/docker-compose.yml up -d --build
deploy/fix send …        # and the other commands of section 2.5
```

The raw FIX lines come from the archive, with the `FixArchivePrinter` command of the Operator's
Manual, 6.8 (stream 1 inbound, 2 outbound; market data from `/dev/shm/fix-aeron-md` and
`/data-md/logs`).
