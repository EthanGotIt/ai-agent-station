---
name: Commerce Guardian Agent
description: 可恢复的订单售后 Thread 工作台，用清晰的业务事实、授权节点和执行回执帮助定位问题。
colors:
  canvas: "#f4f7f6"
  surface: "#ffffff"
  surface-muted: "#f6f9f8"
  ink: "#142522"
  muted: "#526660"
  quiet: "#5e716a"
  line: "#d3e0db"
  line-strong: "#b7cbc3"
  rail: "#142522"
  route: "#176c60"
  route-strong: "#0e554b"
  route-soft: "#d8ebe5"
  amber: "#925515"
  amber-soft: "#f6eadb"
  danger: "#a33b37"
  danger-soft: "#f8e2df"
  success: "#20735c"
  success-soft: "#dceddf"
  focus: "#188c79"
  button-ink: "#f3fbf8"
  scrim: "rgb(7 20 17 / 25%)"
  scrim-strong: "rgb(7 20 17 / 48%)"
typography:
  headline:
    fontFamily: 'Segoe UI Variable, Microsoft YaHei, PingFang SC, ui-sans-serif, system-ui, sans-serif'
    fontSize: "0.98rem"
    fontWeight: 700
    letterSpacing: "-0.02em"
  title:
    fontFamily: 'Segoe UI Variable, Microsoft YaHei, PingFang SC, ui-sans-serif, system-ui, sans-serif'
    fontSize: "16px"
    fontWeight: 700
    letterSpacing: "-0.02em"
  body:
    fontFamily: 'Segoe UI Variable, Microsoft YaHei, PingFang SC, ui-sans-serif, system-ui, sans-serif'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.58
  label:
    fontFamily: 'Cascadia Mono, SFMono-Regular, Consolas, monospace'
    fontSize: "12px"
    fontWeight: 800
    letterSpacing: "0.12em"
  mono:
    fontFamily: 'Cascadia Mono, SFMono-Regular, Consolas, monospace'
    fontSize: "12px"
    fontWeight: 500
rounded:
  control: "8px"
  card: "10px"
  panel: "12px"
  modal: "14px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
  xxl: "24px"
components:
  button-primary:
    backgroundColor: "{colors.route}"
    textColor: "{colors.button-ink}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
    height: "44px"
  button-secondary:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
    height: "44px"
  status-success:
    backgroundColor: "{colors.success-soft}"
    textColor: "{colors.success}"
    rounded: "{rounded.pill}"
    padding: "4px 7px"
  status-warning:
    backgroundColor: "{colors.amber-soft}"
    textColor: "{colors.amber}"
    rounded: "{rounded.pill}"
    padding: "4px 7px"
  status-error:
    backgroundColor: "{colors.danger-soft}"
    textColor: "{colors.danger}"
    rounded: "{rounded.pill}"
    padding: "4px 7px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "9px 10px"
    height: "44px"
  order-card:
    backgroundColor: "{colors.surface-muted}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "13px"
  decision-card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.panel}"
    padding: "18px"
  item-inspector:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    padding: "0 18px"
  navigation:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    padding: "20px 18px"
    width: "clamp(280px, 23.5vw, 376px)"
---

# Design System: Commerce Guardian Agent

## Overview

**Creative North Star: "The Dispatch Ledger"**

Commerce Guardian Agent is an Operate-mode workbench for developers and technical reviewers who need to resolve an order issue, see the business fact that supports it, and understand whether an external action is merely queued, awaiting confirmation, or actually verified. The visual language treats the interface as a dispatch ledger: calm surfaces, strict sequence, compact status signals, and an explicit receipt at the point where the user makes a decision.

The incumbent world is intentionally quiet and operational. A cool mist canvas holds white work surfaces; thin dividers establish the Thread, Turn, and Item hierarchy without turning the page into a monitoring dashboard. Green marks the normal route, amber marks an unresolved handoff, and red marks a fault. Chinese UI copy leads, while mono numerals and identifiers make timestamps, sequence numbers, and order IDs easy to scan.

**Key Characteristics:**
- Ledger-like, business-first hierarchy with technology details available on demand.
- Cool white-and-mist surfaces with thin sage dividers instead of heavy card chrome.
- Route teal for normal flow, amber for waiting or unverified external state, and red for failure.
- Responsive three-column workbench that becomes focused drawers on narrower screens.
- Structured order facts and action receipts remain authoritative after refresh or reconnection.

**The Ledger-First Rule.** Show the business result and its current receipt before exposing the execution trail; the Item sequence is an inspector, not the main conversation.

## Colors

The palette is a restrained pine-and-sage system with three semantic signal families. The light theme is the primary reference; the dark theme overrides the same semantic roles through `prefers-color-scheme` and must not be reimplemented with ad-hoc component colors.

### Primary
- **Route Teal** (`{colors.route}`): The normal business path, primary submit actions, active timeline dots, and positive inline emphasis.
- **Deep Route Teal** (`{colors.route-strong}`): Text and icon contrast on route washes, IDs that need emphasis, and the hover destination for primary controls.
- **Route Wash** (`{colors.route-soft}`): Quiet selected states, workflow summaries, action receipts in progress, and inline code-like facts.

### Secondary
- **Signal Amber** (`{colors.amber}`): Waiting for a user answer, unverified external state, and connection recovery.
- **Signal Wash** (`{colors.amber-soft}`): Background for waiting states and recovery guidance; always pair it with a text label or icon.

### Tertiary
- **Fault Red** (`{colors.danger}`): Failed turns, exhausted retries, validation errors, and destructive action affordances.
- **Fault Wash** (`{colors.danger-soft}`): The supporting surface for errors; never use red as the only state encoding.

### Neutral
- **Cool Mist** (`{colors.canvas}`): The page canvas and central conversation background.
- **Paper Surface** (`{colors.surface}`): The top bar, side panels, composer, cards, and dialogs.
- **Quiet Fog** (`{colors.surface-muted}`): Secondary cards, selected list rows, summaries, and technical detail blocks.
- **Deep Pine Ink** (`{colors.ink}`): Primary readable text and headings.
- **Sage Slate** (`{colors.muted}`) and **Soft Sage** (`{colors.quiet}`): Supporting copy, timestamps, and recovery details.
- **Mist Divider** (`{colors.line}`) and **Assertive Divider** (`{colors.line-strong}`): One-pixel structural borders and control strokes.
- **Pine Rail** (`{colors.rail}`): The Agent avatar and the deepest neutral anchor.
- **Focus Teal** (`{colors.focus}`): The 3px keyboard focus ring and caret accent.
- **Button Mint** (`{colors.button-ink}`): Text on the primary route button.
- **Calm Scrim** (`{colors.scrim}`) and **Strong Scrim** (`{colors.scrim-strong}`): Modal and drawer backdrops.

**The Semantic Signal Rule.** A color must name a real operational state and travel with readable copy, iconography, or structure; never turn the palette into decoration.

## Typography

**Display Font:** none; this is an operational console, not a marketing surface.
**Body Font:** Segoe UI Variable (with Microsoft YaHei, PingFang SC, ui-sans-serif, system-ui, sans-serif)
**Label/Mono Font:** Cascadia Mono (with SFMono-Regular, Consolas, monospace)

**Character:** The variable sans keeps Chinese and interface prose calm at a compact 14px baseline. Cascadia Mono is reserved for measurements, identifiers, timestamps, sequence numbers, and uppercase section metadata so technical information feels precise rather than ornamental.

### Hierarchy
- **Headline** (700, `0.98rem`, tight tracking): The product name in the sticky top bar.
- **Title** (700, `16px`, tight tracking): The current Thread title, empty-state heading, and decision-card heading.
- **Card title** (700, `15px`): Turn actor names and structured order headings.
- **Body** (400, `14px`, `1.58` line-height): Requests, Agent conclusions, prompts, and business facts; keep long prose within the central 760–820px reading measure.
- **Label** (800, `12px`, `0.12em` tracking): Sparse uppercase section markers such as `THREADS`, `CURRENT THREAD`, and `STRUCTURED FACTS`.
- **Mono metadata** (500, `12px`): Order IDs, timestamps, sequence counts, fingerprints, and controlled JSON.

**The Measured Metadata Rule.** Use mono for data that benefits from alignment or auditability, never for ordinary Chinese copy or an entire screen.

## Layout

The desktop workbench is a full-height, sticky-top-bar composition. The top bar is 64px high and contains the product lockup on the left and the current demo account disclosure on the right. Below it, the workspace grid uses a Thread rail of `clamp(280px, 23.5vw, 376px)`, a fluid central Turn stream, and an optional Item inspector of `clamp(340px, 27.5vw, 440px)`. The central conversation, structured facts, and composer share an `820px` maximum width; the business fact and decision cards cap at `760px`.

The rail is a white, border-separated list with a search field, compact rows, and inline rename. The central area is the reading surface: a context bar, scrollable Turn records, and a persistent composer. The inspector is a ledger-style column that prioritizes the latest persisted Items and reveals older entries by 80-item pages. The main rhythm is built from 8, 10, 12, 14, 16, 18, 20, and 24px steps, with larger separation before new content groups.

At widths below 1180px, the inspector becomes a right-side drawer with a strong scrim and a maximum 410px content width. At 760px and below, the Thread rail becomes a left-side drawer and the inspector covers the viewport; the top bar becomes 60px tall, the central padding tightens to 15px, and action groups stack. At 430px, composer actions and recovery actions become full-width rows. All controls preserve a minimum 44px hit target.

## Elevation & Depth

The system is flat by default and relies on tonal layering plus one-pixel dividers for the permanent hierarchy. Shadows appear only where a surface temporarily rises above the workbench: the composer, account/help popovers, recovery card, mobile Thread drawer, and modal dialog. Modal and drawer scrims are stable hit surfaces and do not change shade on hover.

### Shadow Vocabulary
- **Ambient surface** (`0 12px 30px rgb(20 53 47 / 8%)`): Context fields, help menus, and order overflow menus.
- **Close surface** (`0 6px 16px rgb(20 53 47 / 8%)`): Composer and connection recovery card, where a small lift separates an active recovery or input surface from the canvas.
- **Modal depth** (`0 24px 60px rgb(20 53 47 / 18%), 0 2px 8px rgb(20 53 47 / 10%)`): QuestionCard and Workflow Checkpoint surfaces that temporarily interrupt the workbench.

**The Tonal Layer Rule.** Permanent structure uses white, mist, and dividers; elevation is reserved for transient focus, recovery, and confirmation surfaces.

## Shapes

The form language is gently squared and tactile without becoming playful: 8px controls, 8–10px business cards, 12px panels, 14px modals, and 999px status pills. Borders are one pixel and usually sage-toned; there are no thick colored rails on cards. Avatars and timeline markers are circular, while the selected Thread row uses a one-pixel inset route marker to preserve the list silhouette. Textareas remain vertically resizable within decision cards but the composer keeps its own compact 70px minimum.

## Components

### Buttons
- **Shape:** 8px radius, 44px minimum height, 8px vertical and 12px horizontal padding.
- **Primary:** Route teal fill with Button Mint text; use for send, confirm, retry connection, and other committed actions.
- **Hover / Focus:** Deep Route Teal on hover with a one-pixel upward lift and a soft colored shadow; all focusable controls use a 3px Focus Teal outline with 3px offset.
- **Secondary / Ghost:** Transparent surface with Assertive Divider stroke and Ink text; hover adds Route Wash and Deep Route Teal without a shadow.
- **Compact icon:** The same 44px hit target with a 15px SVG icon; use for close, rename, and drawer controls.

### Chips
- **Style:** 12px semibold/800 labels, 4px × 7px padding, and a 999px radius.
- **State:** Success green indicates active or completed; amber indicates queued, waiting, or unverified; red indicates failed or cancelled; route teal is reserved for order-domain states such as paid, shipped, or refunded.

### Cards / Containers
- **Turn facts:** White, 10px corners, 15px padding, Assertive Divider border, and no permanent shadow. It is the semantic wrapper for structured order facts.
- **Order card:** Quiet Fog background, 8px corners, 13px padding, thin divider, and an action row separated by a one-pixel top rule.
- **Decision card:** White, 12px corners, 18px padding, and a route or amber boundary depending on whether it is a Workflow confirmation or a missing-information question.
- **Internal padding:** Prefer the 8/10/12/13/15/18px observed steps; add space between groups instead of nesting extra cards.

### Inputs / Fields
- **Style:** White background, Assertive Divider stroke, 8px radius, 44px minimum height, and 9px × 10px padding. The composer removes the inner stroke and supplies a 14px rounded outer surface.
- **Focus:** Focus Teal outline and route caret; validation errors use Fault Red stroke plus a subtle red ring.
- **Error / Disabled:** Error copy is adjacent and announced through a stable `aria-describedby` ID; disabled controls retain layout and reduce opacity rather than disappearing.

### Navigation
- **Style:** The Thread rail is a white, sticky-height column separated by a single right border. Rows are compact, left-aligned, and divided by one-pixel rules; the active row uses Quiet Fog plus a one-pixel inset Route Teal marker.
- **Typography:** Thread names use readable sans; context IDs and statuses use 12px metadata styles. Search is a 7px rounded inline field.
- **Mobile treatment:** Below 760px, the rail becomes a left drawer with a Strong Scrim, focus trap, Escape close, and a 330px/88vw maximum width.

### Status Receipt
Action receipts stay inside the source order card. They combine an icon, action name, human-readable state, and—when appropriate—`重新查询` or `人工重试`; this prevents a second global success/failure channel from competing with the business fact.

### Decision Modal
QuestionCard collects only the missing fields and offers “继续” or “结束本次问题”. Workflow Checkpoint shows action, order, impact, and fact fingerprint, then offers “确认并执行” or “拒绝执行”. Both use the same centered modal shell, stable scrim, close button, Escape behavior, independent scrolling, and focus management; the Composer remains visible but disabled underneath.

### Item Inspector
The inspector is a light ledger rather than a second conversation. Each Item row carries a mono sequence number, type, timestamp, and controlled JSON; a vertical one-pixel connector ties the sequence together. Sensitive keys and raw Thinking are never rendered. On narrow screens it becomes an overlay drawer with the same content and a close-first focus order.

### Logistics Timeline
The timeline is a compact ordered list inside an order card. A one-pixel neutral connector joins 10px route-outlined dots; each event pairs a status, location, description, and mono timestamp. Empty timelines use a calm sentence rather than a blank container.

## Do's and Don'ts

### Do:
- **Do** put structured business facts and the current action receipt ahead of technical execution details.
- **Do** use Route Teal, Signal Amber, and Fault Red only for their semantic operational states, with text and icons alongside color.
- **Do** keep permanent surfaces flat and let thin dividers, tonal layering, and spacing establish hierarchy.
- **Do** use Cascadia Mono for order IDs, timestamps, sequence values, and controlled JSON.
- **Do** preserve the single Thread → Turn → Item relationship when adding new UI patterns.
- **Do** keep keyboard focus, Escape close, reduced-motion, forced-colors, and 44px hit targets intact.

### Don't:
- **Don't** introduce a marketing hero, metric dashboard, decorative gradients, glass blur, or progress-ring substitute for the ledger.
- **Don't** expose raw Thinking or let a generic `TURN_STATE=COMPLETED` label override a failed, waiting, or unverified business result.
- **Don't** create a second global action dialog or success badge when the order card already owns the receipt.
- **Don't** use color as the only signal, or use mono as a costume for ordinary copy.
- **Don't** add hidden/recoverable order actions that the product does not support; deletion remains explicitly irreversible.
- **Don't** replace the calm dispatch-list metaphor with a dense monitoring-wall composition.
