import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { LiveIntakePilot } from './LiveIntakePilot'
import './synthetic-intake-pilot.css'

createRoot(document.getElementById('live-intake-pilot-root')!).render(<StrictMode><LiveIntakePilot /></StrictMode>)
