# Stepped Progress Indicator (Circle Moving Across Steps) — UI Design Breakdown

## What It Is

A **stepped progress indicator** (also called a **stepper** or **step wizard**) shows the user where they are in a multi-step form — like a claim submission form. Each step is represented by a **circle** (numbered or icon-based), connected by a line/bar. As the user completes a step, the active circle animates or "moves" to the next one.

---

## Common Visual Layouts

### 1. Horizontal Stepper (Most Common)
```
 [1] ──── [2] ──── [3] ──── [4]
  ↑                    ↑
 Completed          Current
```

- Circles arranged left to right on a horizontal bar
- **Completed steps**: Filled circle (green/blue) with checkmark
- **Current step**: Outlined circle with animated fill or pulsing effect
- **Future steps**: Grey/disabled circles
- The "circle moving" effect is achieved by the active state **shifting right** as the user progresses

### 2. Vertical Stepper
```
 [✓] Step 1 — Completed
 [●] Step 2 — Current (active)
 [○] Step 3 — Upcoming
 [○] Step 4 — Upcoming
```

- Circles stacked vertically, connected by a line on the left
- Common in forms with many fields per step (like detailed claim forms)

### 3. Circular / Radial Stepper
```
        [1]
     /       \
  [4]         [2]
     \       /
        [3]
```

- Steps arranged in a circle; the active step rotates or highlights sequentially
- Less common but visually striking for dashboards

---

## How the "Circle Moving" Animation Works

The animation that makes the circle appear to "move" to the next form typically uses:

### State Transitions

| State | Visual | CSS/Code Technique |
|-------|--------|-------------------|
| **Inactive** | Grey outline circle | `opacity: 0.4` |
| **Active/Current** | Filled circle with animation | `transform: scale(1.1)` + pulse |
| **Completed** | Green filled with checkmark | `background-color: #4CAF50` |
| **Transition** | Line fills from left to right | `width: 0 → 100%` animation |

### Key Animation Techniques

1. **Progress Line Fill** — The connecting line between circles animates from 0% to 100% width using CSS `@keyframes` or JavaScript `requestAnimationFrame`

2. **Circle Scale & Pulse** — When a step becomes active, the circle scales up briefly (`scale(1.2 → 1.0)`) to draw attention

3. **Checkmark Draw** — On completion, a checkmark draws itself inside the circle using SVG `stroke-dasharray` + `stroke-dashoffset` animation

4. **Slide Transition** — The form content slides in from right (like a carousel) while the progress bar updates

---

## UI Component Library Examples

### Material UI (React) — Stepper
```jsx
import Stepper from '@mui/material/Stepper';
import Step from '@mui/material/Step';
import StepLabel from '@mui/material/StepLabel';

<Stepper activeStep={currentStep}>
  {steps.map(label => (
    <Step key={label}>
      <StepLabel>{label}</StepLabel>
    </Step>
  ))}
</Stepper>
```
- Built-in circle indicators with numbered/icon steps
- Connecting line auto-animates when `activeStep` changes
- Supports completed state with checkmark icons

### Ant Design (React) — Steps
```jsx
import { Steps } from 'antd';

<Steps
  current={1}
  items={[
    { title: 'Personal Info' },
    { title: 'Claim Details' },
    { title: 'Documents' },
    { title: 'Review & Submit' },
  ]}
/>
```
- Clean circle design with animated transitions
- Progress line fills between completed steps
- `status` prop for error/process/finish states

### Bootstrap-based — Custom
- Use `nav-pills` with custom CSS for circle bullets
- Progress bar fills underneath the circles
- JavaScript/jQuery to manage step navigation

---

## Example: Claim Form Flow UI (Step-by-Step)

Let's say a claim form has 4 steps:

```
[1] Personal Info  ──  [2] Claim Details  ──  [3] Upload Docs  ──  [4] Review
```

### Visual States Over Time

| Step | Initial | After Step 1 | After Step 2 | After Step 3 | After Step 4 |
|------|---------|-------------|-------------|-------------|-------------|
| 1 | ⭕ Active | ✅ Done | ✅ Done | ✅ Done | ✅ Done |
| 2 | ⭕ | ⭕ Active | ✅ Done | ✅ Done | ✅ Done |
| 3 | ⭕ | ⭕ | ⭕ Active | ✅ Done | ✅ Done |
| 4 | ⭕ | ⭕ | ⭕ | ⭕ Active | ✅ Done |

**Line animation**: When moving from Step 1 to Step 2, the line between Circle 1 and Circle 2 fills from 0% → 100% over ~300ms.

**Circle animation**: Circle 2 scales up with a smooth CSS transition:
```css
.step-circle.active {
  transform: scale(1.15);
  box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.3);
  transition: all 0.3s ease;
}
```

---

## Tech Stack Used for This UI

### Frontend Frameworks
| Tool | Use Case |
|------|---------|
| **React / Next.js** | Dynamic single-page app with state management |
| **Vue.js / Nuxt** | Reactive step transitions |
| **Angular** | Enterprise claim portals |
| **jQuery** | Older systems, simpler implementations |

### UI Libraries (Pre-built Steppers)
| Library | Framework | Features |
|---------|-----------|----------|
| Material UI Stepper | React | Most popular, full-featured |
| Ant Design Steps | React | Clean enterprise look |
| Chakra UI Stepper | React | Lightweight, customizable |
| PrimeNG Steps | Angular | Enterprise-ready |
| Vuetify Stepper | Vue | Beautiful animations |
| Bootstrap + custom CSS | Any | Lightweight, full control |

### Animation Libraries
- **CSS Transitions & Keyframes** — Most common (lightweight, performant)
- **Framer Motion** (React) — Smooth spring-based animations:
  ```jsx
  <motion.div
    animate={{ scale: isActive ? 1.2 : 1 }}
    transition={{ type: "spring", stiffness: 300 }}
  />
  ```
- **GSAP** — For complex timeline-based animations
- **Anime.js** — Lightweight JS animation engine

### State Management (for tracking current step)
- React: `useState`, `useReducer`, or Redux/Zustand
- Vue: `ref()`, `reactive()`, Pinia
- Angular: RxJS BehaviorSubject or NgRx

---

## Design Best Practices for Claim Form Steppers

1. **Show step labels** — Each circle needs a text label below (e.g., "Personal Info", "Documents") so users know what's ahead
2. **Allow backtracking** — Users should be able to click previous circles to go back and edit
3. **Validate on step change** — Don't let users proceed if current step has errors (show red indicator on the circle)
4. **Animate but don't slow** — Keep transitions under 400ms; anything longer frustrates users
5. **Mobile responsive** — On small screens, collapse to a compact stepper or show only the current + next step
6. **Error state** — If validation fails, show a red circle with exclamation icon on the failing step

---

## Common UI Patterns for "Circle Moving to Next Form"

### Pattern A: Animated Progress Line (Most Common)
```
Initial:      ○───○───○───○
After Step 1: ●═══○───○───○    (line fills between 1→2 partially)
After Step 2: ●═══●═══○───○
```

### Pattern B: Carousel Slider
- Form content slides left/right as steps change
- The progress bar at top updates simultaneously
- Gives a strong "moving forward" feeling

### Pattern C: Circle Becomes Checkmark
```
Before: [1]     After: [✓]  (with green fill animation)
```
- The number morphs into a checkmark upon completion
- Commonly seen in Material UI Stepper

---

## Quick Code Demo (React + CSS)

```jsx
function ClaimFormStepper({ steps, currentStep }) {
  return (
    <div className="stepper">
      <div className="progress-line">
        <div
          className="progress-fill"
          style={{ width: `${(currentStep / (steps.length - 1)) * 100}%` }}
        />
      </div>
      {steps.map((step, i) => (
        <div
          key={i}
          className={`step-circle ${
            i < currentStep ? 'completed' : i === currentStep ? 'active' : ''
          }`}
        >
          {i < currentStep ? '✓' : i + 1}
          <span className="step-label">{step}</span>
        </div>
      ))}
    </div>
  );
}
```

CSS animation for the fill:
```css
.progress-fill {
  transition: width 0.4s ease-in-out;
  background: #3B82F6;
  height: 4px;
}

.step-circle.active {
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(59,130,246,0.4); }
  50% { box-shadow: 0 0 0 8px rgba(59,130,246,0); }
}
```
