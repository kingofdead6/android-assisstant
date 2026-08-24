# web

The project website — React + Tailwind, built with Vite. One page explaining how
John turns a spoken sentence into a permission-gated action.

## Running it

```bash
cd web
npm install
npm run dev        # http://localhost:5173
```

## Building it

```bash
npm run build      # → web/dist
npm run preview    # serve the built output
```

Asset paths are relative (`base: './'` in `vite.config.js`), so `dist/` works
from a domain root, a GitHub Pages project subpath, or opened straight off disk.

## Deploying it

Any static host. Point it at `web/` with build command `npm run build` and
publish directory `web/dist`. Nothing server-side, no environment variables.

## Layout

```
web/
  index.html              shell + the pre-paint theme script
  tailwind.config.js      design tokens: colour, type, scale
  vite.config.js
  src/
    main.jsx              entry
    App.jsx               section order
    index.css             base + component layers
    content.js            all page copy and data
    components/
      Primitives.jsx      Section, SectionHead, Rule, ThemeToggle
      Hero.jsx  Pipeline.jsx  RiskLadder.jsx
      Vocabulary.jsx  Engines.jsx  Honesty.jsx
      Install.jsx  Footer.jsx
```

## Editing it

**Copy and data** live in `src/content.js` — the pipeline stages, risk levels,
tool names, engine specs and honesty notes. Editing text rarely means touching a
component.

**Design tokens** live in `tailwind.config.js`. Colours are defined as
`light`/`dark` pairs (`accent-light`, `accent-dark`), so a token change
propagates everywhere; components never hardcode a hex value.

### One trap worth knowing

The theme class sits on `<html>`. Tailwind compiles a `dark:` utility used
inside `@apply` to `<selector>:is(.dark *)` — a **descendant** combinator, which
`body` can never match, since it is html's child. A `dark:bg-…` applied to
`body` that way silently never fires, and the page renders light text on a light
ground.

`src/index.css` therefore writes every themed rule as an explicit `.dark
<selector>` pair using `theme()` values. Using `dark:` directly in JSX markup is
fine — that path compiles correctly. The rule only bites inside `@apply`.

## Keeping it honest

The tool vocabulary in `content.js` mirrors the real tool names registered in
`app/src/main/java/com/john/assistant/di/ToolModule.kt`. Nothing enforces that
automatically, so keep them in step when tools are added or renamed.
