import { Section, SectionHead } from './Primitives'
import { risks } from '../content'

// Written out rather than interpolated, so Tailwind's scanner can see every
// class it needs to generate.
const tones = {
  low: 'text-low-light dark:text-low-dark bg-low-light/[0.12] dark:bg-low-dark/[0.14]',
  med: 'text-med-light dark:text-med-dark bg-med-light/[0.12] dark:bg-med-dark/[0.14]',
  high: 'text-high-light dark:text-high-dark bg-high-light/[0.12] dark:bg-high-dark/[0.14]',
}

export default function RiskLadder() {
  return (
    <Section id="risk">
      <SectionHead
        eyebrow="Risk ladder"
        title="Not every action deserves the same trust"
      >
        Every tool declares its own risk level. You choose the threshold at which John
        stops and asks — the default is to confirm anything medium or above.
      </SectionHead>

      <div className="grid gap-5 [grid-template-columns:repeat(auto-fit,minmax(230px,1fr))]">
        {risks.map((r) => (
          <article key={r.level} className="card">
            <span
              className={`self-start font-mono text-[0.72rem] tracking-[0.09em] uppercase
                          px-2.5 py-1 rounded-[5px] ${tones[r.tone]}`}
            >
              {r.level}
            </span>
            <h3 className="text-[1.16rem] -tracking-[0.012em]">{r.title}</h3>
            <p className="text-[0.95rem] text-ink-2-light dark:text-ink-2-dark">{r.body}</p>
          </article>
        ))}
      </div>
    </Section>
  )
}
