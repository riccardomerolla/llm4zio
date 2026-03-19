# Design System Conventions

## Overview

All design system primitives are implemented as Lit 3 web components under this directory (`design-system/`).

## Prefix

All custom elements use the `ab-` prefix, e.g. `ab-badge`, `ab-spinner`, `ab-card`.

## Light DOM

All components render into the light DOM so that Tailwind CSS utility classes work without Shadow DOM encapsulation:

```js
createRenderRoot() { return this; }
```

## Properties

Declare properties via `static properties = { ... }` with explicit types:

```js
static properties = {
  text:    { type: String },
  variant: { type: String },
  active:  { type: Boolean },
  count:   { type: Number },
};
```

Always initialise in `constructor()`.

## Events

Dispatch events through `CustomEvent` with `bubbles: true, composed: true`:

```js
this.dispatchEvent(new CustomEvent('ab-close', { bubbles: true, composed: true }));
```

## Import pattern

```js
import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';
```

## Component template

```js
import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbExample extends LitElement {
  static properties = {
    text: { type: String },
  };

  constructor() {
    super();
    this.text = '';
  }

  createRenderRoot() { return this; }

  render() {
    return html`<span class="text-sm text-gray-200">${this.text}</span>`;
  }
}

customElements.define('ab-example', AbExample);
```

## Prop naming

- Use lowercase, dash-free names in JS (`type`, `message`, `isOpen` → avoid; prefer `open`)
- Map to kebab-case HTML attributes automatically (Lit handles this)
- Boolean props use `{ type: Boolean }` and are reflected with `reflect: true` when used for CSS

## Components

| Element            | File                  | Description                   |
|--------------------|-----------------------|-------------------------------|
| `ab-badge`         | `ab-badge.js`         | Status/label badge            |
| `ab-spinner`       | `ab-spinner.js`       | Loading spinner               |
| `ab-status`        | `ab-status.js`        | Health status indicator       |
| `ab-card`          | `ab-card.js`          | Content card container        |
| `ab-modal`         | `ab-modal.js`         | Modal dialog                  |
| `ab-progress-bar`  | `ab-progress-bar.js`  | Animated progress bar         |
| `ab-data-table`    | `ab-data-table.js`    | Sortable data table           |
| `ab-toast`         | `ab-toast.js`         | Toast notification stack      |
