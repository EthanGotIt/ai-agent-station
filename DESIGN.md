---
name: Agent Workbench Dispatch Ledger
description: A calm personal Agent workbench with a graphite rail, mineral canvas, and explicit operational states.
colors:
  canvas: "#e8ebe2"
  surface: "#fbfaf5"
  surface-muted: "#f0f2e9"
  surface-strong: "#e2e7dc"
  ink: "#202722"
  muted: "#566358"
  quiet: "#626e64"
  line: "#d4d8cd"
  line-strong: "#b7c0b1"
  rail: "#222a25"
  rail-deep: "#1a211d"
  rail-ink: "#eff1e8"
  rail-muted: "#b9c1b6"
  jade: "#4f6957"
  jade-strong: "#365344"
  jade-soft: "#dce6d9"
  vermilion: "#b84934"
  vermilion-strong: "#963b2a"
  vermilion-soft: "#f4ded7"
  warning: "#8c6716"
  warning-soft: "#f4e8be"
  success: "#386843"
  success-soft: "#dbead9"
  focus: "#1d6b4c"
  action-ink: "#fffaf6"
  rail-selected: "#526658"
typography:
  display:
    fontFamily: "Aptos, Microsoft YaHei, PingFang SC, ui-sans-serif, system-ui, sans-serif"
    fontSize: "clamp(1.65rem, 2.4vw, 2.45rem)"
    lineHeight: 1.13
    letterSpacing: "-0.035em"
  body:
    fontFamily: "Aptos, Microsoft YaHei, PingFang SC, ui-sans-serif, system-ui, sans-serif"
  label:
    fontFamily: "Aptos, Microsoft YaHei, PingFang SC, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.82rem"
    fontWeight: 750
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, Consolas, monospace"
    fontSize: "0.8rem"
rounded:
  field: "8px"
  control: "9px"
  navigation: "10px"
  surface: "12px"
  pill: "999px"
spacing:
  compact: "8px"
  control: "12px"
  card: "16px"
  pane: "24px"
  page: "clamp(24px, 3vw, 44px)"
components:
  button-primary:
    backgroundColor: "{colors.vermilion}"
    textColor: "{colors.action-ink}"
    rounded: "{rounded.control}"
    padding: "9px 13px"
  button-primary-hover:
    backgroundColor: "{colors.vermilion-strong}"
  button-secondary:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "9px 13px"
  field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.field}"
    padding: "9px 10px"
  navigation-active:
    backgroundColor: "{colors.rail-selected}"
    textColor: "{colors.rail-ink}"
    rounded: "{rounded.navigation}"
    padding: "12px 11px"
  status-running:
    backgroundColor: "{colors.warning-soft}"
    textColor: "{colors.warning}"
    rounded: "{rounded.pill}"
    padding: "4px 8px"
  status-success:
    backgroundColor: "{colors.success-soft}"
    textColor: "{colors.success}"
    rounded: "{rounded.pill}"
    padding: "4px 8px"
  status-error:
    backgroundColor: "{colors.vermilion-soft}"
    textColor: "{colors.vermilion}"
    rounded: "{rounded.pill}"
    padding: "4px 8px"
---

# Design System: Agent Workbench Dispatch Ledger

## Overview

**Creative North Star: "Dispatch Ledger"**

Dispatch Ledger makes an Agent round feel like a legible work record rather than a generic SaaS dashboard. A graphite rail holds stable workspace orientation; the mineral canvas keeps requests, structured results, and forms calm enough to read for long sessions. Jade marks the selected workspace, chosen route, and healthy workflow movement. Vermilion is deliberately reserved for actions that write, confirm, cancel, or fail.

This is a personal, local school-recruitment workbench. Its visual confidence comes from clear boundaries, real state, and a restrained amount of polish—not production claims, decorative metrics, neon, or a log-viewer aesthetic. The desktop conversation canvas and its narrow execution inspector are peers: the former explains the business round, while the latter preserves technical evidence on demand.

**Key Characteristics:**

- Dense but breathable operational reading, with persistent context and short, bounded panes.
- Mineral surfaces, graphite navigation, jade progress, and sparingly used vermilion actions.
- Rounded rectangular records with thin dividers and ambient, low-contrast elevation.
- System light and dark palettes, plus mobile stacked panes rather than compressed desktop columns.

## Colors

The palette is an operational mineral system: neutral surfaces carry the reading load, while jade, vermilion, amber, and success green each keep a single, intelligible state meaning.

### Primary

- **Ledger Jade:** selection, route confirmation, timeline markers, icons, and the positive secondary-action hover state.
- **Deep Ledger Jade:** stronger jade text and mobile active navigation; use when jade must remain legible on a pale jade fill.
- **Jade Wash:** selected mobile navigation, route tags, and the subtle affirmative-action fill.

### Secondary

- **Oxidized Vermilion:** the default filled action and the failure/destructive signal. It is the only saturated call to action in a quiet pane.
- **Deep Vermilion:** hovered filled action and stronger error copy.
- **Vermilion Wash:** failed, cancelled, rejected, and destructive status backgrounds.
- **Amber Watch:** running, waiting, and pending-review status text.
- **Amber Wash:** the background for states that still need attention.
- **Completion Green:** completed, active, and refund-processing status text.
- **Completion Wash:** the background for resolved positive states.

### Neutral

- **Mineral Canvas:** the page field behind all workspaces.
- **Paper Surface:** cards, inspector, fields, and the default elevated container.
- **Muted and Strong Surfaces:** shallow tonal separation for headings, queues, skeletons, and hover planes instead of added decoration.
- **Graphite Rail and Deep Rail:** persistent navigation with a darker visual mass than the work canvas.
- **Ink, Muted, and Quiet Text:** ordered reading contrast for primary copy, supporting copy, and timestamps/placeholders. Muted and Quiet are intentional post-review contrast tokens, not decorative grays.
- **Fine and Strong Rules:** hairline boundaries that divide records, cards, columns, and scrollable timelines.
- **Rail Ink and Rail Muted:** high-contrast and supporting text within the graphite rail.

**The Semantic Accent Rule.** Jade means selected, routed, or advancing; vermilion means write, confirm, cancel, fail, or delete. Do not use either simply to decorate a neutral surface.

**The System-Theme Rule.** The CSS custom-property names remain semantic in dark mode; their values change with the system preference so content and state roles never change meaning.

## Typography

**Display Font:** Aptos, with Microsoft YaHei, PingFang SC, and system sans-serif fallbacks.
**Body Font:** Aptos, with the same CJK-capable and system fallbacks.
**Label/Mono Font:** UI monospace stack for tool arguments and JSON-like structured fallbacks.

**Character:** Contemporary system typography keeps Chinese operational copy compact, direct, and comfortably scannable. The system avoids a separate brand display face; hierarchy comes from scale, weight, tight heading tracking, and disciplined line length.

### Hierarchy

- **Display** (responsive display scale, 1.13 line-height): workspace promise and primary task heading.
- **Section title** (compact title scale with tight tracking): scenario, inspector, queue, and detail headings.
- **Body** (comfortable 1.5–1.7 line-height): explanatory copy, conversation content, and empty states; conversation copy is capped around 74ch.
- **Label** (750 weight, compact label scale): fields and short interactive labels that must remain readable at operating density.
- **Metadata** (compact 0.72–0.82rem scale, often tabular numerals): timestamps, state detail, helper copy, and trace evidence.
- **Mono** (compact mono scale): tool arguments and unstructured structured-result fallbacks.

**The Reading-First Rule.** Use the large display treatment once per workspace; let section headings, labels, and metadata do the rest of the hierarchy without adding ornamental type styles.

## Layout

The desktop shell is a two-column frame: a 244px sticky graphite rail and a fluid application page. The page has a 76px sticky, translucent top bar, then a centered workbench with a maximum width of 1560px and responsive page padding. The default Agent workspace is a conversation canvas plus a 290–340px execution inspector separated by a 24px gap. Scenarios begin as a four-column ledger and become two columns at 1180px.

At 960px the inspector, review queue, and memory list stack beneath their primary content. At 720px the rail disappears, the page uses compact gutters, the scenario ledger becomes one column, and the three workspaces move to a fixed bottom navigation. The composer shifts above that navigation; no desktop pane is squeezed into the mobile viewport. At 460px, record controls and detail actions may become full width.

The spacing rhythm is compact inside controls and cards, then opens at pane and page boundaries. Sticky context is reserved for the rail, top bar, composer, and desktop inspector; it makes the active task available without repeating it in every record.

## Elevation & Depth

This is a tonal-layer system with two quiet ambient shadows. Paper surfaces sit on the mineral canvas through thin rules and soft lift; no card uses heavy, high-contrast shadowing. The top bar and composer use a translucent surface plus a 14px backdrop blur only when sticky context overlaps scrolling content.

### Shadow Vocabulary

- **Ambient surface:** the larger low-opacity shadow for sticky composer, contextual field popover, and broader elevated surfaces.
- **Close surface:** the tighter low-opacity shadow for scenario ledgers, inspector, decisions, review, and memory containers.

**The Ledger-Not-Glass Rule.** Tonal boundaries and fine rules establish most depth. Blur is limited to the two sticky context layers; do not turn ordinary cards into frosted glass.

## Shapes

Surfaces are gently rounded rectangles: fields use the smallest corner, standard actions use the control corner, navigation is slightly softer, and durable records use the shared surface corner. Statuses, avatars, timeline points, trace markers, and scroll thumbs become fully round only when their compact scale makes a pill or circle meaningful. Borders are thin and mineral rather than decorative; scenario cells intentionally share one outer container and internal dividers.

**The Shared-Record Rule.** Use a single rounded outer boundary for related data or actions, then divide its interior with rules. Do not give every item an independently floating card.

## Components

### Buttons

**Decisive operational controls.**

- **Primary:** filled vermilion action with the control corner and compact control padding. Hover deepens the action, raises it by one pixel, and adds a small vermilion lift.
- **Secondary:** outlined, transparent control for non-writing actions. Hover moves into the jade semantic family without elevation.
- **Danger:** outlined vermilion at rest, then fills vermilion on hover; use for delete and similarly irreversible local operations.
- **Disabled:** reduced opacity and a not-allowed cursor; preserve the action’s shape and label rather than removing it.

### Inputs / Fields

**Quiet, bounded evidence entry.**

- **Style:** paper surface, strong mineral rule, field corner, compact inner padding, and vermilion caret.
- **Focus:** a visible focus outline with offset; composer text focus moves the outline inward to respect its sticky container.
- **Textarea:** vertically resizable; the composer uses a borderless textarea inside its own bordered, blurred surface.

### Navigation

**Stable orientation rather than decorative tab chrome.**

- **Desktop:** a vertical graphite rail holds brand, three full-label workspace controls, and a status footnote. The active workspace is a darker sage block with a close shadow.
- **Mobile:** the same three destinations become a fixed, blurred bottom nav with icon-above-label controls. The active item uses jade wash and deep jade text.
- **Interaction:** hover brightens unselected rail items; selection is communicated with color, background, and `aria-current`, not color alone.

### Status Chips

**Compact, semantic state witnesses.**

- **Shape:** fully pill-shaped, compactly padded, bold, and single-line.
- **Running / waiting / pending review:** amber text on amber wash.
- **Completed / active / processing:** success green on completion wash.
- **Cancelled / failed / rejected / deleted:** vermilion on vermilion wash.

### Scenario Ledger

**One-click entry to real seeded scenarios.**

- **Container:** a paper surface with the shared surface corner, close elevation, and an outer rule.
- **Cells:** four equal desktop actions separated by internal rules; each contains a jade icon, concise title, and muted explanation.
- **Responsive behavior:** two cells per row at the intermediate breakpoint and a rule-divided vertical list on mobile.

### Conversation Turn

**The readable record of one Agent round.**

- **Structure:** request and response use compact circular avatars beside a content column, with each turn divided by a fine rule.
- **State:** route is a jade pill; status stays inline with Agent metadata; conversation content remains bounded to readable measure.
- **Motion:** a new turn reveals by a small upward settle over 230ms; loading uses a restrained cursor pulse.

### Structured Result Card

**A business result, not a raw payload.**

- **Container:** muted surface, surface corner, fine rule, and 16px internal padding.
- **Content:** jade section label, two-column field list, optional item list, or a jade-marked vertical timeline. On narrow screens, fields stack to one column.
- **Fallback:** tool and JSON-style content uses the mono role and wraps rather than overflowing.

### Decision Card

**Explicit boundaries for user-gated work.**

- **Container:** paper surface with jade outline, close elevation, and generous card padding.
- **Signal:** a vermilion alert icon leads the heading, then the user-facing question or confirmation and its choices.
- **Use:** Workflow question cards and ReAct write confirmations share this frame so a pending decision looks intentional, not like an error.

### Execution Inspector

**A narrow, optional technical ledger.**

- **Container:** a desktop-sticky paper panel with muted heading band and close elevation; it returns to normal flow when the canvas stacks.
- **Trace:** the most recent events live on a rule-based vertical timeline with round jade markers; error markers switch to vermilion.
- **Empty and ready states:** use a jade technical icon and a compact neutral indicator, retaining the same spatial footprint as a populated trace.

### Review / Memory Master Detail

**Auditable lists with a persistent detail surface.**

- **Container:** shared paper record with close elevation, a muted list pane, and a divider-led detail pane.
- **Rows:** transparent at rest; hover and selected state gain paper fill and strong-rule outline rather than a new floating card.
- **Responsive behavior:** list and detail stack at the tablet breakpoint; mobile keeps each pane’s reading order intact.

## Do's and Don'ts

### Do:

- **Do** keep the graphite rail on desktop and use the mobile bottom navigation below 720px.
- **Do** assign jade to selection, routing, and affirmative workflow visibility; assign vermilion to write, confirm, cancel, failure, and deletion.
- **Do** use the defined compact controls, fine rules, and shared outer containers to keep dense operational data legible.
- **Do** honor system light/dark themes, visible focus outlines, reduced-motion behavior, and the forced-colors fallback.
- **Do** keep state feedback within the existing quick 150–250ms range and preserve static readability when it is disabled.

### Don't:

- **Don't** introduce blue SaaS gradients, neon accents, fabricated metrics, or production-service claims.
- **Don't** make every row or field a floating card; group related records inside one bordered surface.
- **Don't** use jade and vermilion as interchangeable decoration or hide state solely in color.
- **Don't** compress the desktop rail-plus-inspector layout into a narrow mobile column; stack panes and retain the bottom navigation.
- **Don't** add prolonged animation, parallax, or ambient movement to an operational workspace.
