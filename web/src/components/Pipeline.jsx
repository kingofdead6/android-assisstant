import { Section, SectionHead } from './Primitives'
import { pipeline } from '../content'

export default function Pipeline() {
  return (
    <Section id="pipeline">
      <SectionHead
        eyebrow="The turn pipeline"
        title="What happens between your voice and your phone"
      >
        Seven stages. The model sits in the middle — and the three stages after it
        exist specifically to constrain what it produced.
      </SectionHead>

      <ol className="grid gap-3 list-none p-0 m-0
                     [grid-template-columns:repeat(auto-fit,minmax(150px,1fr))]">
        {pipeline.map((step) => (
          <li
            key={step.n}
            className={[
              'rounded-[10px] p-4 flex flex-col gap-1.5 border',
              step.gate
                ? 'border-accent-light dark:border-accent-dark bg-accent-light/10 dark:bg-accent-dark/[0.14]'
                : 'border-line-light dark:border-line-dark bg-surface-light dark:bg-surface-dark',
            ].join(' ')}
          >
            <span className="font-mono text-[0.7rem] tracking-[0.1em]
                             text-accent-light dark:text-accent-dark">
              {step.n}
            </span>
            <b className="text-[0.97rem] font-semibold -tracking-[0.01em]">{step.title}</b>
            <small className="text-[0.83rem] leading-relaxed
                              text-ink-3-light dark:text-ink-3-dark">
              {step.body}
            </small>
          </li>
        ))}
      </ol>

      {/* The same pipeline as the README's diagram. Scrolls on its own so the
          page body never scrolls sideways. */}
      <pre
        aria-label="The pipeline as a diagram"
        className="overflow-x-auto rounded-[10px] px-5 py-4 m-0
                   bg-raised-light dark:bg-raised-dark
                   border border-line-light dark:border-line-dark
                   font-mono text-[0.85rem] leading-[1.75]"
      >
<span className="text-ink-3-light dark:text-ink-3-dark">{`voice → wake word → speech-to-text → context → LLM
                                                 ↓
`}</span>
<span className="text-accent-light dark:text-accent-dark">{`                                         tool + arguments
`}</span>
<span className="text-ink-3-light dark:text-ink-3-dark">{`                                                 ↓
`}</span>
<span className="text-accent-light dark:text-accent-dark">{`                              validate → permission → confirm`}</span>
<span className="text-ink-3-light dark:text-ink-3-dark">{` → execute
                                                 ↓
                                        result → speech`}</span>
      </pre>
    </Section>
  )
}
