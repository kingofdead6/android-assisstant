import { useEffect, useState } from 'react'

/** A section heading block: eyebrow, title, optional standfirst. */
export function SectionHead({ eyebrow, title, children }) {
  return (
    <div className="flex flex-col gap-4 max-w-measure">
      <p className="eyebrow">{eyebrow}</p>
      <h2 className="text-section">{title}</h2>
      {children && (
        <p className="text-ink-2-light dark:text-ink-2-dark">{children}</p>
      )}
    </div>
  )
}

/** Vertical rhythm for a page section. */
export function Section({ children, id }) {
  return (
    <section id={id} className="py-[clamp(4rem,9vw,7rem)]">
      <div className="shell flex flex-col gap-12">{children}</div>
    </section>
  )
}

export function Rule() {
  return <hr className="rule" />
}

/**
 * Theme toggle.
 *
 * Reads the class the pre-paint script in index.html already set, so the
 * button's state matches what is on screen rather than re-deriving it.
 */
export function ThemeToggle() {
  const [dark, setDark] = useState(
    () => typeof document !== 'undefined' &&
      document.documentElement.classList.contains('dark')
  )

  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark)
    try {
      localStorage.setItem('john-theme', dark ? 'dark' : 'light')
    } catch {
      // Storage blocked — the toggle still works for this session.
    }
  }, [dark])

  return (
    <button
      type="button"
      onClick={() => setDark((d) => !d)}
      aria-pressed={dark}
      className="font-mono text-[0.72rem] tracking-[0.1em] uppercase px-3 py-2 rounded-full
                 border border-line-strong-light dark:border-line-strong-dark
                 text-ink-2-light dark:text-ink-2-dark
                 bg-surface-light dark:bg-surface-dark
                 hover:text-accent-light dark:hover:text-accent-dark
                 hover:border-accent-light dark:hover:border-accent-dark
                 transition-colors"
    >
      {dark ? 'Light' : 'Dark'}
    </button>
  )
}
