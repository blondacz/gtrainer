import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { SyntheticIntakePilot } from './SyntheticIntakePilot'
import './synthetic-intake-pilot.css'

createRoot(document.getElementById('synthetic-intake-pilot-root')!).render(<StrictMode><SyntheticIntakePilot /></StrictMode>)
