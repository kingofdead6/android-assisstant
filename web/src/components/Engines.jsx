import { Section, SectionHead } from './Primitives'
import { engines } from '../content'

export default function Engines() {
  return (
    <Section id="engines">
      <SectionHead eyebrow="Two engines" title="Pick where the thinking happens">
        Whichever card reads <strong className="font-semibold">In use</strong> is the
        one answering. There is never any ambiguity about where your words are going.
      </SectionHead>

      <div className="grid gap-5 [grid-template-columns:repeat(auto-fit,minmax(300px,1fr))]">
        {engines.map((e) => (
          <article
            key={e.name}
            className={[
              'card relative',
              e.active
                ? 'border-accent-light dark:border-accent-dark ring-[3px] ring-accent-light/10 dark:ring-accent-dark/[0.14]'
                : '',
            ].join(' ')}
          >
            {e.active && (
              <span className="absolute top-[1.1rem] right-[1.1rem] font-mono text-[0.66rem]
                               tracking-[0.11em] uppercase
                               text-accent-light dark:text-accent-dark">
                In use
              </span>
            )}
            <h3 className="text-[1.16rem] -tracking-[0.012em]">{e.name}</h3>
            <p className="text-[0.95rem] text-ink-2-light dark:text-ink-2-dark">{e.body}</p>

            <dl className="grid [grid-template-columns:auto_1fr] gap-x-4 gap-y-1.5 text-[0.88rem] mt-1">
              {e.spec.map(([k, v]) => (
                <div key={k} className="contents">
                  <dt className="font-mono text-[0.75rem] uppercase tracking-[0.05em] pt-0.5
                                 text-ink-3-light dark:text-ink-3-dark">
                    {k}
                  </dt>
                  <dd className="m-0 text-ink-2-light dark:text-ink-2-dark">{v}</dd>
                </div>
              ))}
            </dl>
          </article>
        ))}
      </div>

      <p className="max-w-measure text-[0.95rem] text-ink-2-light dark:text-ink-2-dark">
        And a third path that needs neither: a deterministic phrase matcher answers
        “pause”, “what's my battery” and dozens more in microseconds, with no inference
        at all. It runs <em>first</em>, always — so a missing, loading or broken model
        can never stop John working.
      </p>
    </Section>
  )
}
