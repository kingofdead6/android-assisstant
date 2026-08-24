import Hero from './components/Hero'
import Pipeline from './components/Pipeline'
import RiskLadder from './components/RiskLadder'
import Vocabulary from './components/Vocabulary'
import Engines from './components/Engines'
import Honesty from './components/Honesty'
import Install from './components/Install'
import Footer from './components/Footer'
import { Rule } from './components/Primitives'

export default function App() {
  return (
    <>
      <Hero />
      <main>
        <Pipeline />
        <Rule />
        <RiskLadder />
        <Rule />
        <Vocabulary />
        <Rule />
        <Engines />
        <Rule />
        <Honesty />
        <Rule />
        <Install />
      </main>
      <Footer />
    </>
  )
}
