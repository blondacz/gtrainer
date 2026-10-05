import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { DevelopmentApp } from './DevelopmentApp'
import './development.css'

createRoot(document.getElementById('root')!).render(<StrictMode><DevelopmentApp /></StrictMode>)
