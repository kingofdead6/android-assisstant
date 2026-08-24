import { Section, SectionHead } from './Primitives'
import { tools } from '../content'

export default function Vocabulary() {
  return (
    <Section id="vocabulary">
      <SectionHead
        eyebrow="Fixed vocabulary"
        title="The model can only say these words"
      >
        This is the entire action surface. A model that invents a tool name outside
        this list produces nothing — there is no path from an unrecognised name to a
        running action.
      </SectionHead>

      <ul className="flex flex-wrap gap-1.5 list-none p-0 m-0">
        {tools.map((t) => (
          <li key={t} className="chip">{t}</li>
        ))}
      </ul>
    </Section>
  )
}
