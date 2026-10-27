// my-elf's tab on Elf Desk: the newest items of your feed, with what you haven't read first. Done marks one read.
// It runs in the desk's sandboxed frame: everything goes through `elf`, and only what elf.yaml declares.
import {
    LitElement, html, css, shared, cardStyles, spinner, ago, runTime, count, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';

const ID = /^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.json$/;

function atOf(file) {
    const m = ID.exec(file);
    return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]) : null;
}

/** The saved runs, newest first: [{id, file, at}]. */
async function runs() {
    return (await elf.list('runs')).filter(f => ID.test(f.name)).slice(0, 20)
        .map(f => ({ id: f.name.slice(0, -5), file: f.name, at: atOf(f.name) }));
}

/** The ids you marked read. */
async function doneIds() {
    return new Set((await elf.readJson('done', 'done.json'))?.ids ?? []);
}

class MyElfTab extends LitElement {

    static properties = {
        _runs: { state: true },
        _picked: { state: true },
        _run: { state: true },
        _done: { state: true },
        _loading: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .summary { margin: 0; padding: 18px 22px; font-size: 16px; max-width: 46rem; }
        ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
        li { padding: 14px 18px; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 4px 16px; align-items: start; }
        li.read { opacity: 0.6; }
        li a { font-weight: 600; color: var(--ink); }
        li p { margin: 0; grid-column: 1; font-size: 14px; color: var(--muted);
            display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
        li .when { grid-column: 1; font-size: 13px; color: var(--muted); }
        li button { grid-row: 1 / span 3; grid-column: 2; }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .skeleton.big { height: 40vh; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._picked = null;
        this._loading = true;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        // A run started or ended (the refresh, a schedule): read again.
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        this._error = null;
        try {
            this._runs = await runs();
            const pick = this._runs.find(r => r.id === this._picked) ?? this._runs[0];
            this._run = pick ? { ...(await elf.readJson('runs', pick.file)), at: pick.at } : null;
            this._done = await doneIds();
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    async _markRead(item) {
        this._done = new Set([...this._done, item.id]);
        try {
            await elf.act('done', { id: item.id });
        } catch (e) {
            this._error = `Couldn't mark it read: ${e.message}`;
            this._done = await doneIds();
        }
    }

    _item(item) {
        const read = this._done?.has(item.id);
        return html`<li class="panel ${read ? 'read' : ''}">
            ${item.url ? html`<a href=${item.url}>${item.title || item.url}</a>` : html`<strong>${item.title}</strong>`}
            ${item.summary ? html`<p>${item.summary}</p>` : ''}
            ${item.at && !Number.isNaN(Date.parse(item.at)) ? html`<span class="when">${ago(new Date(item.at))}</span>` : ''}
            ${read ? '' : html`<button class="quiet" @click=${() => this._markRead(item)}>Done</button>`}
        </li>`;
    }

    render() {
        const r = this._run;
        const items = r?.items ?? [];
        // What you haven't read first, then the rest in the feed's order.
        const sorted = [...items.filter(i => !this._done?.has(i.id)), ...items.filter(i => this._done?.has(i.id))];
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>My elf</h1>
                    <p class="sub">${r ? `${count(items.length, 'item')}, read ${runTime(r.at).toLowerCase()}.`
                        : this._loading ? '' : 'Nothing read yet.'}</p>
                </div>${settingsButton(null, 'My elf settings')}</div>
                <elf-run-bar command="read" label="Read the feed" .runs=${(this._runs ?? []).map(x => ({ id: x.id, label: runTime(x.at) }))}
                    .picked=${this._picked} note="Reads the feed you set and saves its newest items here. Sends nothing."
                    @run-picked=${e => { this._picked = e.detail; this._load(); }}
                    @run-finished=${() => { this._picked = null; this._load(); }}></elf-run-bar>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !r ? html`<div class="skeleton big"></div>` : ''}
                ${r?.summary ? html`<p class="panel summary">${r.summary}</p>` : ''}
                ${r ? (sorted.length ? html`<ul>${sorted.map(i => this._item(i))}</ul>`
                        : html`<p class="panel empty">The feed had no items.</p>`)
                    : !this._loading ? html`<p class="panel empty">Set the feed in Settings, then Read the feed.</p>` : ''}
            </div>
        `;
    }
}

/** The dashboard card: one sentence on what's new, and it asks for your attention while anything is unread. */
class MyElfCard extends LitElement {

    static properties = { _unread: { state: true }, _at: { state: true }, _state: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            const [newest] = await runs();
            const run = newest ? await elf.readJson('runs', newest.file) : null;
            const done = await doneIds();
            this._unread = run ? (run.items ?? []).filter(i => !done.has(i.id)).length : null;
            this._at = newest?.at;
            this._state = await elf.state();
        } catch (e) {
            this._unread = null;
        }
        this._loaded = true;
        elf.card({ attention: this._unread > 0 });
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const say = this._unread == null ? html`Nothing read yet. <a href="#">Read the feed</a>.`
            : this._unread ? html`<a href="#">${count(this._unread, 'new item')}</a> in your feed.`
            : 'You have read everything in your feed.';
        const running = this._state?.running?.read;
        return html`<p class="say">${say}</p>
            <div class="meta">${running ? html`<span class="running" role="status">${spinner}Reading, started ${ago(running)}</span>`
                : this._at ? html`<span>Read ${runTime(this._at)}</span>` : ''}</div>`;
    }
}

customElements.define('my-elf-tab', MyElfTab);
customElements.define('my-elf-card', MyElfCard);
document.body.append(document.createElement(elf.view === 'card' ? 'my-elf-card' : 'my-elf-tab'));
