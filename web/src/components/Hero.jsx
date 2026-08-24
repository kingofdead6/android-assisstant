import { ThemeToggle } from './Primitives'

const badges = [
  'Android 8.0+',
  'Runs with no model at all',
  'LiteRT-LM on-device',
  'Kotlin · Compose · Hilt',
]

export default function Hero() {
  return (
    <header className="relative overflow-hidden border-b border-line-light dark:border-line-dark
                       py-[clamp(4.5rem,11vw,8rem)]">
      {/* Ambient glow behind the orb. Decorative only. */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-[30%] left-1/2 -translate-x-1/2
                   w-[min(900px,120%)] aspect-square rounded-full
                   bg-[radial-gradient(circle,rgba(46,95,208,0.22)_0%,transparent_62%)]
                   dark:bg-[radial-gradient(circle,rgba(110,155,255,0.28)_0%,transparent_62%)]"
      />

      <div className="shell relative flex flex-col gap-12">
        <div className="flex justify-end">
          <ThemeToggle />
        </div>

        <div className="flex flex-col gap-7">
          <p className="eyebrow">Local-first Android assistant</p>
          <h1 className="text-hero">
            The model picks a tool.
            <br />
            The{' '}
            <em className="not-italic text-accent-light dark:text-accent-dark">app</em>{' '}
            decides if it runs.
          </h1>
          <p className="text-lede text-ink-2-light dark:text-ink-2-dark max-w-[54ch]">
            John is not a chatbot with a microphone attached. Its language model has
            exactly one job — choose a single tool from a fixed list and fill in its
            arguments. Everything after that is ordinary, auditable application code.
          </p>
        </div>

        <div className="flex items-center gap-5 flex-wrap">
          <div
            role="img"
            aria-label="John's listening orb"
            className="w-[58px] h-[58px] rounded-full shrink-0 animate-breathe
                       bg-[radial-gradient(circle_at_38%_32%,#9DBBFF_0%,#2E5FD0_52%,#1B3E96_100%)]
                       shadow-[0_0_0_8px_rgba(46,95,208,0.10),0_0_34px_rgba(46,95,208,0.22)]
                       dark:shadow-[0_0_0_8px_rgba(110,155,255,0.14),0_0_34px_rgba(110,155,255,0.28)]"
          />
          <div className="border-l-2 border-accent-light dark:border-accent-dark pl-4">
            <span className="block font-mono text-[0.78rem] tracking-[0.08em] uppercase
                             text-ink-3-light dark:text-ink-3-dark mb-0.5">
              You say
            </span>
            <p className="font-mono text-[0.95rem]">
              “Hey John, send Mom a WhatsApp saying I'll be home at eight.”
            </p>
          </div>
        </div>

        <ul className="flex flex-wrap gap-2 list-none p-0 m-0">
          {badges.map((b) => (
            <li
              key={b}
              className="font-mono text-[0.75rem] px-3 py-1.5 rounded-full
                         border border-line-strong-light dark:border-line-strong-dark
                         text-ink-2-light dark:text-ink-2-dark
                         bg-surface-light dark:bg-surface-dark"
            >
              {b}
            </li>
          ))}
        </ul>
      </div>
    </header>
  )
}
