/**
 * Builds the adapter's two documents from Markdown, as most-exchange builds its Operator's Manual:
 *
 *   operator  docs/manual/operator/src  ->  docs/OperatorManual.pdf      (for whoever runs the adapter)
 *   roe       docs/manual/roe/src       ->  docs/RulesOfEngagement.pdf   (for a participant's FIX developers)
 *
 * Markdown in, one paginated HTML document out, then Chrome's print engine, with most-exchange's
 * stylesheet so the two projects' documents read as one set.
 *
 *   npm install && npm run build        # both
 *   node build.mjs operator             # one
 *   node build.mjs --html-only          # stop at build/<doc>.html, the fast loop for the stylesheet
 *
 * CHROME_PATH overrides the browser.
 */
import MarkdownIt from 'markdown-it';
import puppeteer from 'puppeteer-core';
import { readFileSync, writeFileSync, readdirSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const BUILD = join(HERE, 'build');
const ASSETS = join(HERE, 'assets');

const DOCS = {
  operator: {
    dir: join(HERE, 'operator'),
    pdf: resolve(HERE, '..', 'OperatorManual.pdf'),
    title: "most-exchange FIX adapter -- Operator's Manual",
    footer: "most-exchange FIX adapter &mdash; Operator's Manual",
  },
  roe: {
    dir: join(HERE, 'roe'),
    pdf: resolve(HERE, '..', 'RulesOfEngagement.pdf'),
    title: 'most-exchange FIX -- Rules of Engagement',
    footer: 'most-exchange FIX &mdash; Rules of Engagement',
  },
};

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);

const md = new MarkdownIt({ html: true, linkify: false, typographer: true });

/**
 * `::: kind Title` ... `:::` becomes a callout aside. Four kinds for four jobs: `term` defines a
 * piece of vocabulary where it is first needed, `note` and `warning` qualify the text around them,
 * and `todo` marks something specified but not built. A pre-processing pass, so the body still goes
 * through the normal renderer: callouts contain tables and code.
 */
function expandCallouts(text) {
  const out = [];
  const stack = [];
  for (const line of text.split('\n')) {
    const open = /^:::\s+(term|note|warning|todo)(?:\s+(.*))?$/.exec(line.trim());
    if (open) {
      const [, kind, title] = open;
      const heading = title && title.trim().length ? title.trim() : defaultTitle(kind);
      out.push(`<aside class="callout callout-${kind}">`, `<p class="callout-title">${escapeHtml(heading)}</p>`, '');
      stack.push(kind);
      continue;
    }
    if (line.trim() === ':::' && stack.length) {
      stack.pop();
      out.push('', '</aside>');
      continue;
    }
    out.push(line);
  }
  if (stack.length) throw new Error(`unclosed callout: ${stack.join(', ')}`);
  return out.join('\n');
}

const defaultTitle = (kind) =>
  ({ term: 'Term', note: 'Note', warning: 'Warning', todo: 'Not implemented' })[kind];

const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** `<!-- pagebreak -->` starts a new printed page. */
const expandPageBreaks = (text) => text.replace(/<!--\s*pagebreak\s*-->/g, '<div class="pagebreak"></div>');

/** A paragraph that is only an image becomes a numbered figure; the alt text is its caption. */
function numberFigures(html, chapter) {
  let n = 0;
  return html.replace(/<p>(<img src="([^"]+)" alt="([^"]*)"[^>]*>)<\/p>/g, (_, img, src, alt) => {
    n += 1;
    const caption = alt ? `<figcaption>Figure ${chapter}.${n} &nbsp;${alt}</figcaption>` : '';
    const cls = src.endsWith('.svg') ? 'figure figure-diagram' : 'figure figure-screenshot';
    return `<figure class="${cls}">${img}${caption}</figure>`;
  });
}

const slug = (text) =>
  text.toLowerCase().replace(/<[^>]+>/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

/** Ids from the heading's own section number, so cross-references survive an edit. */
function anchorHeadings(html, chapterIndex) {
  const toc = [];
  const withIds = html.replace(/<h([123])>([\s\S]*?)<\/h\1>/g, (_, level, inner) => {
    const plain = inner.replace(/<[^>]+>/g, '').trim();
    const id = `s-${chapterIndex}-${slug(plain)}`;
    toc.push({ level: Number(level), text: plain, id });
    return `<h${level} id="${id}">${inner}</h${level}>`;
  });
  return { html: withIds, toc };
}

function renderToc(entries) {
  const items = entries
    .filter((e) => e.level <= 2)
    .map((e) => {
      const [number, ...rest] = e.text.split(/\s+/);
      const numbered = /^[0-9A-Z]+(\.[0-9]+)*\.?$/.test(number) && /[0-9]/.test(number);
      const num = numbered ? number.replace(/\.$/, '') : '';
      const label = numbered ? rest.join(' ') : e.text;
      return `<li class="toc-l${e.level}"><a href="#${e.id}"><span class="toc-num">${escapeHtml(num)}</span><span class="toc-text">${escapeHtml(label)}</span></a></li>`;
    })
    .join('\n');
  return `<nav class="toc"><h1>Contents</h1><ul>${items}</ul></nav>`;
}

function build(name) {
  const doc = DOCS[name];
  const src = join(doc.dir, 'src');
  if (!existsSync(BUILD)) mkdirSync(BUILD, { recursive: true });

  const chapters = readdirSync(src).filter((f) => f.endsWith('.md')).sort();
  if (!chapters.length) throw new Error(`no chapters in ${src}`);

  let body = '';
  const toc = [];
  chapters.forEach((file, i) => {
    const raw = readFileSync(join(src, file), 'utf8');
    const html = md
      .render(expandPageBreaks(expandCallouts(raw)))
      // Chapters refer to ../../assets/ so the Markdown reads correctly on its own; the document is
      // written to build/, so the references are made absolute.
      .replace(/src="(\.\.\/\.\.\/)?assets\//g, `src="${pathToFileURL(ASSETS).href}/`);
    const anchored = anchorHeadings(numberFigures(html, i + 1), i);
    toc.push(...anchored.toc);
    body += `<section class="chapter" id="chapter-${i}">${anchored.html}</section>\n`;
  });

  const css = readFileSync(join(HERE, 'manual.css'), 'utf8');
  const cover = readFileSync(join(doc.dir, 'cover.html'), 'utf8').replace('{{DATE}}', new Date().toISOString().slice(0, 10));

  const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>${doc.title}</title>
<style>${css}</style>
</head><body>
${cover}
<div class="pagebreak"></div>
${renderToc(toc)}
<div class="pagebreak"></div>
${body}
</body></html>`;

  const htmlPath = join(BUILD, `${name}.html`);
  writeFileSync(htmlPath, html);
  console.log(`html  ${htmlPath}  (${chapters.length} chapters, ${toc.length} headings)`);
  return htmlPath;
}

async function toPdf(browser, name, htmlPath) {
  const doc = DOCS[name];
  const page = await browser.newPage();
  await page.goto(pathToFileURL(htmlPath).href, { waitUntil: 'networkidle0' });
  await page.pdf({
    path: doc.pdf,
    format: 'A4',
    printBackground: true,
    displayHeaderFooter: true,
    margin: { top: '18mm', bottom: '20mm', left: '18mm', right: '18mm' },
    headerTemplate: '<div></div>',
    footerTemplate:
      '<div style="width:100%;font-family:-apple-system,Helvetica,sans-serif;font-size:8pt;' +
      'color:#8a8f98;padding:0 18mm;display:flex;justify-content:space-between;">' +
      `<span>${doc.footer}</span><span class="pageNumber"></span></div>`,
  });
  await page.close();
  console.log(`pdf   ${doc.pdf}`);
}

const names = process.argv.slice(2).filter((a) => !a.startsWith('--'));
for (const n of names) if (!DOCS[n]) throw new Error(`unknown document ${n}: one of ${Object.keys(DOCS).join(', ')}`);
const selected = names.length ? names : Object.keys(DOCS);
const built = selected.map((n) => [n, build(n)]);

if (!process.argv.includes('--html-only')) {
  const executablePath = CHROME_CANDIDATES.find((p) => existsSync(p));
  if (!executablePath) throw new Error('no Chrome found. Set CHROME_PATH, or run with --html-only and print build/<doc>.html by hand.');
  const browser = await puppeteer.launch({ executablePath, headless: 'new', args: ['--no-sandbox', '--font-render-hinting=none'] });
  try {
    for (const [n, path] of built) await toPdf(browser, n, path);
  } finally {
    await browser.close();
  }
}
