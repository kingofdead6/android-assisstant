import { REPO } from '../content'

export default function Footer() {
  return (
    <footer className="py-12 border-t border-line-light dark:border-line-dark">
      <div className="shell">
        <p className="text-[0.88rem] text-ink-3-light dark:text-ink-3-dark">
          John — a voice-controlled, local-first assistant for Android. Source on{' '}
          <a
            href={REPO}
            className="text-accent-light dark:text-accent-dark underline underline-offset-2"
          >
            GitHub
          </a>
          . Models are licensed separately by their publishers; John shows each licence
          before anything downloads.
        </p>
      </div>
    </footer>
  )
}
