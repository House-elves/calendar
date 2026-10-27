// calendar's tab on Elf Desk: your calendars together in a day, week or month view, and each event's details with
// its Join link. Its dashboard card says when your next meeting starts. It only reads what the elf saved.
// It runs in the desk's sandboxed frame: everything goes through `elf`, and only what elf.yaml declares.
import {
    LitElement, html, css, shared, cardStyles, spinner, ago, runTime, count, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';

/** Each calendar's colour, by its slot (1 to 20): the first three are the colours it always had. */
const PALETTE = ['#3b6fd8', '#c2410c', '#2f855a', '#8b5cf6', '#b45309', '#0e7490', '#be185d', '#4d7c0f', '#6d28d9', '#a16207'];
const colour = slot => PALETTE[(slot - 1) % PALETTE.length];
const DAY = 86400000;

async function saved() {
    return elf.readJson('events', 'events.json');
}

/** "2026-10-27" is a day in your own zone, not midnight UTC. */
function dayOf(s) {
    const [y, m, d] = s.split('-').map(Number);
    return new Date(y, m - 1, d);
}

function startOf(e) {
    return e.allDay ? dayOf(e.start) : new Date(e.start);
}

function endOf(e) {
    return e.allDay ? dayOf(e.end) : new Date(e.end);
}

function midnight(d) {
    return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

function addDays(d, n) {
    return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
}

function monday(d) {
    const m = midnight(d);
    return addDays(m, -((m.getDay() + 6) % 7));
}

function sameDay(a, b) {
    return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

const time = d => d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const longDay = d => d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' });

/** "in 25 minutes", "in 2 hours", "tomorrow at 09:30". */
function until(at, now = new Date()) {
    const min = Math.round((at - now) / 60000);
    if (min < 1) return 'now';
    if (min < 60) return `in ${count(min, 'minute')}`;
    if (sameDay(at, now) && min < 180) return `in ${count(Math.round(min / 60), 'hour')}`;
    if (sameDay(at, now)) return `at ${time(at)}`;
    if (sameDay(at, addDays(now, 1))) return `tomorrow at ${time(at)}`;
    return `${at.toLocaleDateString([], { weekday: 'long' })} at ${time(at)}`;
}

/** Your next meeting (a timed event you haven't declined), or the one going on now. */
function nextMeeting(events, now = new Date()) {
    return events.find(e => !e.allDay && !e.declined && endOf(e) > now);
}

class CalendarTab extends LitElement {

    static properties = {
        _data: { state: true },
        _loading: { state: true },
        _error: { state: true },
        _view: { state: true },
        _at: { state: true },
        _hidden: { state: true },
        _open: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 22px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .tools { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
        .tools h2 { margin: 0 8px; font-size: 20px; font-weight: 600; }
        .seg { display: inline-flex; gap: 4px; }
        .seg button[aria-pressed="true"] { background: var(--accent); color: white; border-color: var(--accent); }
        .cals { display: flex; flex-wrap: wrap; gap: 6px 16px; font-size: 14px; }
        .cals label { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
        .dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; flex: none; }
        .spacer { flex: 1; }
        .grid { display: grid; overflow: hidden; padding: 0; }
        .head { display: grid; border-bottom: 1px solid var(--line); font-size: 13px; color: var(--muted); }
        .head div { padding: 8px; text-align: center; }
        .head .today { color: var(--accent); font-weight: 600; }
        .allday { display: grid; border-bottom: 1px solid var(--line); min-height: 6px; }
        .allday div { padding: 3px; display: grid; gap: 3px; align-content: start; }
        .scroll { max-height: 64vh; overflow-y: auto; }
        .hours { display: grid; position: relative; }
        .hour { height: 48px; border-top: 1px solid var(--line); font-size: 11px; color: var(--muted); padding: 2px 6px; box-sizing: border-box; }
        .col { position: relative; border-left: 1px solid var(--line); }
        .now { position: absolute; left: 0; right: 0; height: 2px; background: var(--accent); z-index: 2; }
        .ev { position: absolute; box-sizing: border-box; padding: 2px 6px; border-radius: 6px; overflow: hidden; font-size: 12.5px;
            line-height: 1.3; cursor: pointer; text-align: left; border: 0; color: var(--ink); border-left: 4px solid; }
        .ev.chip { position: static; display: block; width: 100%; white-space: nowrap; text-overflow: ellipsis; }
        .ev.declined { opacity: 0.5; text-decoration: line-through; }
        .ev.tentative { border-left-style: dashed; }
        .ev small { color: var(--muted); }
        .month { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); }
        .month .wd { padding: 8px; font-size: 13px; color: var(--muted); text-align: center; border-bottom: 1px solid var(--line); }
        .cell { min-height: 104px; border-left: 1px solid var(--line); border-bottom: 1px solid var(--line); padding: 4px;
            display: grid; gap: 3px; align-content: start; }
        .cell.other { background: var(--wash, rgba(0, 0, 0, 0.02)); }
        .cell .n { font-size: 12px; color: var(--muted); background: none; border: 0; padding: 2px 4px; justify-self: start; cursor: pointer; }
        .cell .n.today { color: white; background: var(--accent); border-radius: 10px; }
        .more { font-size: 12px; color: var(--muted); background: none; border: 0; padding: 0 4px; cursor: pointer; text-align: left; }
        .detail { padding: 20px 24px; display: grid; gap: 12px; }
        .detail h2 { margin: 0; font-size: 22px; }
        .detail dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 6px 16px; margin: 0; font-size: 14px; }
        .detail dt { color: var(--muted); }
        .detail dd { margin: 0; overflow-wrap: anywhere; }
        .detail pre { margin: 0; white-space: pre-wrap; font: inherit; font-size: 14px; overflow-wrap: anywhere; max-height: 50vh; overflow-y: auto; }
        .detail .row { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
        .people { margin: 0; padding: 0; list-style: none; display: grid; gap: 2px; }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .skeleton.big { height: 50vh; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._loading = true;
        this._view = 'week';
        this._at = midnight(new Date());
        this._hidden = new Set();
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        // Remembered in this browser: the view you last used and the calendars you hid.
        elf.store.get('view').then(v => { if (['day', 'week', 'month'].includes(v)) this._view = v; }).catch(() => {});
        elf.store.get('hidden').then(h => { if (Array.isArray(h)) this._hidden = new Set(h); }).catch(() => {});
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        this._error = null;
        try {
            this._data = await saved();
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    _setView(v) {
        this._view = v;
        elf.store.set('view', v).catch(() => {});
    }

    _toggle(slot) {
        const h = new Set(this._hidden);
        h.has(slot) ? h.delete(slot) : h.add(slot);
        this._hidden = h;
        elf.store.set('hidden', [...h]).catch(() => {});
    }

    _move(step) {
        const a = this._at;
        if (this._view === 'day') this._at = addDays(a, step);
        else if (this._view === 'week') this._at = addDays(a, 7 * step);
        else this._at = new Date(a.getFullYear(), a.getMonth() + step, 1);
    }

    get _events() {
        return (this._data?.events ?? []).filter(e => e.calendars.some(c => !this._hidden.has(c)));
    }

    _colour(e) {
        return colour(e.calendars.find(c => !this._hidden.has(c)) ?? e.calendars[0]);
    }

    _name(slot) {
        return this._data?.calendars?.find(c => c.slot === slot)?.name ?? `Calendar ${slot}`;
    }

    /** The events on a day: [all-day ones, timed ones]. */
    _on(day) {
        const from = day, to = addDays(day, 1);
        const evs = this._events.filter(e => startOf(e) < to && (endOf(e) > from || (+endOf(e) === +startOf(e) && startOf(e) >= from)));
        return [evs.filter(e => e.allDay || endOf(e) - startOf(e) >= DAY), evs.filter(e => !e.allDay && endOf(e) - startOf(e) < DAY)];
    }

    _chip(e, label = true) {
        const c = this._colour(e);
        return html`<button class="ev chip ${e.declined ? 'declined' : ''} ${e.tentative ? 'tentative' : ''}"
            style="border-left-color:${c};background:color-mix(in srgb, ${c} 14%, transparent)"
            title=${e.title} @click=${() => { this._open = e; }}>${label && !e.allDay ? html`<small>${time(startOf(e))}</small> ` : ''}${e.title}</button>`;
    }

    /** Timed events side by side when they overlap: each gets a column within its group. */
    _layout(evs, day) {
        const sorted = [...evs].sort((a, b) => startOf(a) - startOf(b) || endOf(b) - endOf(a));
        const out = [];
        let group = [], groupEnd = 0;
        const flush = () => {
            const cols = Math.max(...group.map(g => g.col)) + 1;
            group.forEach(g => out.push({ ...g, cols }));
            group = [];
        };
        for (const e of sorted) {
            const s = Math.max(+startOf(e), +day), en = Math.min(Math.max(+endOf(e), s + 15 * 60000), +addDays(day, 1));
            if (group.length && s >= groupEnd) flush();
            const used = new Set(group.filter(g => g.end > s).map(g => g.col));
            let col = 0;
            while (used.has(col)) col++;
            group.push({ e, start: s, end: en, col });
            groupEnd = Math.max(groupEnd, en);
        }
        if (group.length) flush();
        return out;
    }

    _days(days) {
        const now = new Date();
        const cols = `52px repeat(${days.length}, minmax(0, 1fr))`;
        const lists = days.map(d => this._on(d));
        return html`<div class="panel grid">
            <div class="head" style="grid-template-columns:${cols}"><div></div>${days.map(d => html`<div class=${sameDay(d, now) ? 'today' : ''}>
                ${d.toLocaleDateString([], days.length > 1 ? { weekday: 'short', day: 'numeric' } : { weekday: 'long', day: 'numeric', month: 'long' })}</div>`)}</div>
            <div class="allday" style="grid-template-columns:${cols}"><div></div>${lists.map(([all]) => html`<div>${all.map(e => this._chip(e, false))}</div>`)}</div>
            <div class="scroll">
                <div class="hours" style="grid-template-columns:${cols}">
                    <div>${Array.from({ length: 24 }, (_, h) => html`<div class="hour">${String(h).padStart(2, '0')}:00</div>`)}</div>
                    ${days.map((d, i) => html`<div class="col">
                        ${Array.from({ length: 24 }, () => html`<div class="hour"></div>`)}
                        ${sameDay(d, now) ? html`<div class="now" style="top:${(now - d) / 3600000 * 48}px"></div>` : ''}
                        ${this._layout(lists[i][1], d).map(({ e, start, end, col, cols: n }) => {
                            const c = this._colour(e);
                            return html`<button class="ev ${e.declined ? 'declined' : ''} ${e.tentative ? 'tentative' : ''}"
                                style="top:${(start - d) / 3600000 * 48}px;height:${Math.max(18, (end - start) / 3600000 * 48 - 2)}px;
                                    left:${col / n * 100}%;width:calc(${100 / n}% - 3px);border-left-color:${c};
                                    background:color-mix(in srgb, ${c} 16%, var(--panel, white))"
                                title=${e.title} @click=${() => { this._open = e; }}>
                                <strong>${e.title}</strong><br><small>${time(startOf(e))}–${time(endOf(e))}</small></button>`;
                        })}</div>`)}
                </div>
            </div>
        </div>`;
    }

    _month() {
        const first = new Date(this._at.getFullYear(), this._at.getMonth(), 1);
        const start = monday(first);
        const now = new Date();
        const cells = Array.from({ length: 42 }, (_, i) => addDays(start, i));
        const weeks = cells[35].getMonth() === first.getMonth() ? 42 : 35;
        return html`<div class="panel grid month">
            ${cells.slice(0, 7).map(d => html`<div class="wd">${d.toLocaleDateString([], { weekday: 'short' })}</div>`)}
            ${cells.slice(0, weeks).map(d => {
                const [all, timed] = this._on(d);
                const evs = [...all, ...timed];
                return html`<div class="cell ${d.getMonth() !== first.getMonth() ? 'other' : ''}">
                    <button class="n ${sameDay(d, now) ? 'today' : ''}" aria-label=${longDay(d)}
                        @click=${() => { this._at = d; this._setView('day'); }}>${d.getDate()}</button>
                    ${evs.slice(0, 3).map(e => this._chip(e))}
                    ${evs.length > 3 ? html`<button class="more" @click=${() => { this._at = d; this._setView('day'); }}>${evs.length - 3} more</button>` : ''}
                </div>`;
            })}
        </div>`;
    }

    /** Text with its links clickable: only the links the elf checked and saved with the event. */
    _linked(text, links) {
        if (!links?.length) return text;
        const parts = [];
        let rest = text;
        while (rest) {
            let at = -1, link = null;
            for (const l of links) {
                const i = rest.indexOf(l);
                if (i >= 0 && (at < 0 || i < at || (i === at && l.length > link.length))) { at = i; link = l; }
            }
            if (at < 0) { parts.push(rest); break; }
            parts.push(rest.slice(0, at), html`<a href=${link}>${link}</a>`);
            rest = rest.slice(at + link.length);
        }
        return parts;
    }

    _detail(e) {
        const s = startOf(e), en = endOf(e);
        const when = e.allDay
            ? (en - s > DAY ? `${longDay(s)} to ${longDay(addDays(en, -1))}` : `${longDay(s)}, all day`)
            : `${longDay(s)}, ${time(s)}–${time(en)}${sameDay(s, new Date(en - 1)) ? '' : ` (${longDay(en)})`}`;
        const who = p => p.name ? `${p.name}${p.email ? ` <${p.email}>` : ''}` : p.email ?? '';
        const answers = { accepted: 'yes', declined: 'no', tentative: 'maybe', 'needs-action': 'not answered' };
        return html`<div class="panel detail" role="dialog" aria-label=${e.title}>
            <div class="row"><h2>${e.title}</h2><span class="spacer"></span>
                ${e.join ? html`<a class="button primary" href=${e.join.url}>Join ${e.join.kind}</a>` : ''}
                <button class="quiet" @click=${() => { this._open = null; }}>Close</button></div>
            <dl>
                <dt>When</dt><dd>${when}${e.declined ? ' (you declined)' : e.tentative ? ' (tentative)' : ''}</dd>
                ${e.location ? html`<dt>Where</dt><dd>${this._linked(e.location, e.links)}</dd>` : ''}
                ${e.organizer ? html`<dt>Organiser</dt><dd>${who(e.organizer)}</dd>` : ''}
                ${e.attendees?.length ? html`<dt>Invited</dt><dd><ul class="people">${e.attendees.map(p => html`<li>${who(p)}${
                    p.answer && answers[p.answer] ? html` <span class="muted">(${answers[p.answer]})</span>` : ''}</li>`)}
                    ${e.moreAttendees ? html`<li class="muted">and ${e.moreAttendees} more</li>` : ''}</ul></dd>` : ''}
                <dt>Calendar</dt><dd>${e.calendars.map((c, i) => html`${i ? ', ' : ''}<span class="dot" style="background:${colour(c)}"></span> ${this._name(c)}`)}</dd>
            </dl>
            ${e.description ? html`<pre>${this._linked(e.description, e.links)}</pre>` : ''}
        </div>`;
    }

    _title() {
        const a = this._at;
        if (this._view === 'day') return longDay(a);
        if (this._view === 'month') return a.toLocaleDateString([], { month: 'long', year: 'numeric' });
        const m = monday(a), s = addDays(m, 6);
        return `${m.toLocaleDateString([], { day: 'numeric', month: 'short' })} – ${s.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' })}`;
    }

    render() {
        const d = this._data;
        const cals = d?.calendars ?? [];
        const names = cals.map(c => c.name);
        const next = d ? nextMeeting(this._events) : null;
        const sub = d ? html`${names.length ? `${names.join(', ')}. ` : ''}Read ${runTime(new Date(d.generatedAt)).toLowerCase()}.`
            : this._loading ? '' : 'Nothing read yet.';
        let body;
        if (this._view === 'day') body = this._days([this._at]);
        else if (this._view === 'week') body = this._days(Array.from({ length: 7 }, (_, i) => addDays(monday(this._at), i)));
        else body = this._month();
        const outside = d && (this._at < dayOf(d.from) || this._at >= dayOf(d.to));
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Calendar</h1>
                    <p class="sub">${sub}</p>
                </div>${settingsButton(null, 'Calendar settings')}</div>
                <elf-run-bar command="refresh" label="Refresh calendars"
                    note="Reads your calendars through their private addresses and saves the events here. It never changes a calendar."
                    @run-finished=${() => this._load()}></elf-run-bar>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${cals.filter(c => c.error).map(c => html`<p class="error" role="alert">Couldn't read ${c.name}: ${c.error}${
                    c.readAt ? ` Showing what was read ${ago(new Date(c.readAt))}.` : ''}</p>`)}
                ${this._loading && !d ? html`<div class="skeleton big"></div>` : ''}
                ${!d && !this._loading ? html`<p class="panel empty">Add a calendar with its private iCal address in Settings,
                    then Refresh calendars. Settings says where to find it in Google Calendar, Outlook and iCloud.</p>` : ''}
                ${d ? html`
                    ${next ? html`<p class="sub">Next: <a href="#" @click=${ev => { ev.preventDefault(); this._open = next; }}>${next.title}</a>
                        ${startOf(next) <= new Date() ? 'is on now' : until(startOf(next))}.
                        ${next.join ? html` <a href=${next.join.url}>Join ${next.join.kind}</a>` : ''}</p>` : ''}
                    <div class="tools">
                        <button class="quiet" @click=${() => { this._at = midnight(new Date()); }}>Today</button>
                        <button class="quiet" aria-label="Back" @click=${() => this._move(-1)}>‹</button>
                        <button class="quiet" aria-label="Forward" @click=${() => this._move(1)}>›</button>
                        <h2>${this._title()}</h2>
                        <span class="spacer"></span>
                        <span class="seg">${['day', 'week', 'month'].map(v => html`<button class="quiet" aria-pressed=${this._view === v}
                            @click=${() => this._setView(v)}>${v[0].toUpperCase() + v.slice(1)}</button>`)}</span>
                    </div>
                    <div class="cals">${cals.map(c => html`<label><input type="checkbox" .checked=${!this._hidden.has(c.slot)}
                        @change=${() => this._toggle(c.slot)}><span class="dot" style="background:${colour(c.slot)}"></span>${c.name}</label>`)}</div>
                    ${outside ? html`<p class="panel empty">This is outside what was read (${longDay(dayOf(d.from))} to ${
                        longDay(addDays(dayOf(d.to), -1))}). Raise Weeks ahead in Settings to read further.</p>` : ''}
                    ${this._open ? this._detail(this._open) : ''}
                    ${body}` : ''}
            </div>
        `;
    }

    updated(changed) {
        // Open the day and week views at the start of the working day, not at midnight.
        if (changed.has('_view') || (changed.has('_data') && !changed.get('_data'))) {
            const s = this.renderRoot.querySelector('.scroll');
            if (s) s.scrollTop = 7 * 48;
        }
    }
}

/** The dashboard card: your next meeting and when it starts. It asks for you when one starts within 10 minutes. */
class CalendarCard extends LitElement {

    static properties = { _data: { state: true }, _state: { state: true }, _loaded: { state: true } };
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
            this._data = await saved();
            this._state = await elf.state();
        } catch (e) {
            this._data = null;
        }
        this._loaded = true;
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const now = new Date();
        const next = this._data ? nextMeeting(this._data.events ?? [], now) : null;
        const soon = next && startOf(next) - now < 10 * 60000;
        elf.card({ attention: !!soon });
        const say = !this._data ? html`Add a calendar in <a href="#">Settings</a>, then Refresh calendars.`
            : !next ? 'No more meetings in your calendars.'
            : startOf(next) <= now ? html`<a href="#">${next.title}</a> is on now.`
            : html`<a href="#">${next.title}</a> ${until(startOf(next), now)}.`;
        const running = this._state?.running?.refresh;
        const failed = this._data?.calendars?.filter(c => c.error).map(c => c.name) ?? [];
        return html`<p class="say">${say}</p>
            <div class="meta">
                ${next?.join ? html`<a href=${next.join.url}>Join ${next.join.kind}</a>` : ''}
                ${running ? html`<span class="running" role="status">${spinner}Reading, started ${ago(running)}</span>`
                    : this._data ? html`<span>Read ${runTime(new Date(this._data.generatedAt))}</span>` : ''}
                ${failed.length ? html`<span>Couldn't read ${failed.join(' and ')}</span>` : ''}
            </div>`;
    }
}

/**
 * Your calendars, at the top of its Settings pop-up: as many as you like, each with a name and its private iCal
 * address. The address goes to the elf's config file and is never shown again: the list says only where it lives.
 */
class CalendarSettings extends LitElement {

    static properties = { _list: { state: true }, _max: { state: true }, _error: { state: true }, _busy: { state: true } };

    static styles = [shared, css`
        :host { display: grid; gap: 14px; }
        ul { margin: 0; padding: 0; list-style: none; display: grid; gap: 6px; }
        li { display: flex; align-items: center; gap: 10px; font-size: 14px; }
        .dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; flex: none; }
        .where { color: var(--muted); }
        .spacer { flex: 1; }
        form { display: grid; grid-template-columns: minmax(8rem, 1fr) minmax(12rem, 3fr) auto; gap: 8px; align-items: end; }
        label { display: grid; gap: 4px; font-size: 13px; color: var(--muted); }
        .help { margin: 0; font-size: 13px; color: var(--muted); }
        .empty { margin: 0; color: var(--muted); font-size: 14px; }
    `];

    connectedCallback() {
        super.connectedCallback();
        this._act('calendars', {});
    }

    /** Asks for one of its list's actions; the answer is always the list as it is now. */
    async _act(action, params) {
        this._busy = true;
        this._error = null;
        try {
            const r = await elf.act(action, params);
            const a = r?.result ?? r;
            if (Array.isArray(a?.calendars)) this._list = a.calendars;
            if (a?.max) this._max = a.max;
            if (a?.error) {
                this._error = a.error;
                return false;
            }
            return true;
        } catch (e) {
            if (!e?.cancelled) this._error = `Couldn't change your calendars: ${e?.message ?? e}`;
            return false;
        } finally {
            this._busy = false;
        }
    }

    async _add(ev) {
        ev.preventDefault();
        const form = ev.target;
        const name = form.name.value.trim(), address = form.address.value.trim();
        if (!name || !address) {
            this._error = 'Give it a name and paste its iCal address.';
            return;
        }
        if (await this._act('add-calendar', { name, address })) {
            form.reset();
            elf.run('refresh').catch(() => {});
        }
    }

    async _remove(c) {
        if (await this._act('remove-calendar', { slot: c.slot })) elf.run('refresh').catch(() => {});
    }

    render() {
        const list = this._list;
        const full = list && this._max && list.length >= this._max;
        return html`
            ${!list ? html`<div class="skeleton"></div>` : list.length ? html`<ul>${list.map(c => html`<li>
                <span class="dot" style="background:${colour(c.slot)}"></span><span>${c.name}</span>
                <span class="where">${c.where}</span><span class="spacer"></span>
                <button class="sm quiet" ?disabled=${this._busy} @click=${() => this._remove(c)}>Remove</button></li>`)}</ul>`
                : html`<p class="empty">No calendars yet. Add one below: as many as you like, from any account.</p>`}
            ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            ${full ? html`<p class="help">That's ${this._max}, as many as it reads. Remove one to add another.</p>` : html`
                <form @submit=${this._add}>
                    <label>Name<input name="name" maxlength="40" placeholder="Work" autocomplete="off"></label>
                    <label>Private iCal address<input name="address" type="password" maxlength="500"
                        placeholder="https://… or webcal://…" autocomplete="off"></label>
                    <button type="submit" ?disabled=${this._busy}>Add calendar</button>
                </form>`}
            <p class="help"><b>Google Calendar</b> (Gmail, Google Workspace): Settings, your calendar, Integrate calendar,
                Secret address in iCal format. If a work account has no such field, your admin has turned it off.
                <b>Outlook</b> (Office 365, Outlook.com): Settings, Calendar, Shared calendars, Publish a calendar with Can view
                all details, then copy the ICS link. <b>iCloud</b>: share the calendar as a Public Calendar and copy its link.
                Any other calendar with an iCal (.ics) address works too. Anyone with the address can read the calendar,
                so it's kept like a password and never shown again.</p>`;
    }
}

customElements.define('calendar-tab', CalendarTab);
customElements.define('calendar-card', CalendarCard);
customElements.define('calendar-settings', CalendarSettings);
document.body.append(document.createElement({ card: 'calendar-card', settings: 'calendar-settings' }[elf.view] ?? 'calendar-tab'));
