import { Section, SectionHead } from './Primitives'
import { honesty } from '../content'

export default function Honesty() {
  return (
    <Section id="honesty">
      <SectionHead eyebrow="Honesty" title="What John won't pretend to do">
        These are real Android limits, not unfinished work. An assistant that overstates
        its reach is worse than one that says where the wall is.
      </SectionHead>

      <div className="grid gap-5 [grid-template-columns:repeat(auto-fit,minmax(300px,1fr))]">
        {honesty.map((h) => (
          <div
            key={h.title}
            className="flex flex-col gap-1 pl-[1.15rem]
                       border-l-2 border-line-strong-light dark:border-line-strong-dark"
          >
            <b className="text-[0.98rem] font-semibold">{h.title}</b>
            <p className="text-[0.93rem] text-ink-2-light dark:text-ink-2-dark">{h.body}</p>
          </div>
        ))}
      </div>
    </Section>
  )
}
