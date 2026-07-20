// Discord notifications for GitHub push / star (watch) events.
//
// Runs on the GitHub Actions runner (Node, no npm deps). All untrusted values (commit messages,
// branch names, usernames, file names) are placed into the payload object and serialized with
// JSON.stringify — never interpolated into a shell or hand-built JSON string — so there is no
// injection surface. On top of that we neutralize @everyone/@here, strip control chars, and
// truncate every string to Discord's documented limits.
//
// Env:
//   GITHUB_EVENT_NAME   "push" | "watch"
//   GITHUB_EVENT_PATH   path to the event payload JSON
//   GITHUB_REPOSITORY   "owner/name"
//   GITHUB_TOKEN        for GitHub API (compare stats, star count)  [optional]
//   DISCORD_WEBHOOK     target webhook URL
//   DRY_RUN=1           print the payload JSON instead of POSTing / calling the API

import fs from 'node:fs';

const {
  GITHUB_EVENT_NAME, GITHUB_EVENT_PATH, GITHUB_REPOSITORY,
  GITHUB_TOKEN, DISCORD_WEBHOOK, DRY_RUN,
} = process.env;

const dryRun = DRY_RUN === '1';
const [owner, repoName] = (GITHUB_REPOSITORY || 'WaterBoss11/BossAddon').split('/');
const LOGO = `https://raw.githubusercontent.com/${owner}/${repoName}/master/assets/MainLogo.png`;

const COLOR = {
  push: 0xE74C3C, // red  — every push event (single commit, batch, merge, any branch)
  star: 0xE74C3C, // red  — new star
  gold: 0xF1C40F, // gold — star milestone (kept distinct for contrast on the rare event)
};

const LIM = { title: 256, desc: 4096, field: 1024, footer: 2048 };

const log = (m) => console.error(`[discord-notify] ${m}`);

// ---- sanitization / formatting -------------------------------------------------------------

// Strip control chars (keep \n = 0x0A and \t = 0x09), neutralize @everyone/@here with a
// zero-width space. JSON.stringify does all JSON escaping, so nothing is hand-quoted.
function clean(s) {
  return String(s ?? '')
    .replace(/[\x00-\x08\x0B\x0C\x0E-\x1F]/g, '')
    .replace(/@(everyone|here)/g, '@\u200b$1');
}
function truncate(s, max) {
  s = String(s ?? '');
  return s.length <= max ? s : s.slice(0, max - 1) + '…';
}
function field(name, value, inline = false) {
  return { name: truncate(clean(name), 256), value: truncate(clean(value) || '—', LIM.field), inline };
}
function prune(obj) {
  for (const k of Object.keys(obj)) if (obj[k] === undefined) delete obj[k];
  return obj;
}

async function ghApi(path) {
  if (dryRun) return null;
  const res = await fetch(`https://api.github.com${path}`, {
    headers: {
      'Accept': 'application/vnd.github+json',
      'User-Agent': 'boss-pvp-discord-notify',
      ...(GITHUB_TOKEN ? { Authorization: `Bearer ${GITHUB_TOKEN}` } : {}),
    },
  });
  if (!res.ok) throw new Error(`GitHub API ${path} -> ${res.status}`);
  return res.json();
}

function readEvent() {
  if (GITHUB_EVENT_PATH && fs.existsSync(GITHUB_EVENT_PATH)) {
    return JSON.parse(fs.readFileSync(GITHUB_EVENT_PATH, 'utf8'));
  }
  return {};
}

// ---- file/stat helpers ---------------------------------------------------------------------

function collectFiles(commits, files) {
  if (files) {
    return {
      added:    files.filter(f => f.status === 'added').map(f => f.filename),
      modified: files.filter(f => ['modified', 'changed', 'renamed'].includes(f.status)).map(f => f.filename),
      removed:  files.filter(f => f.status === 'removed').map(f => f.filename),
    };
  }
  const a = new Set(), m = new Set(), r = new Set();
  for (const c of commits) {
    (c.added || []).forEach(x => a.add(x));
    (c.modified || []).forEach(x => m.add(x));
    (c.removed || []).forEach(x => r.add(x));
  }
  return { added: [...a], modified: [...m], removed: [...r] };
}
function fileCount(f) { return f.added.length + f.modified.length + f.removed.length; }
function fileListBlock(f) {
  const lines = [];
  const cap = 10;
  const push = (emoji, arr) => { for (const x of arr) { if (lines.length >= cap) return; lines.push(`${emoji} \`${x}\``); } };
  push('\u{1F7E2}', f.added);      // green circle
  push('\u{1F7E1}', f.modified);   // yellow circle
  push('\u{1F534}', f.removed);    // red circle
  const total = fileCount(f);
  if (total > lines.length) lines.push(`…and ${total - lines.length} more`);
  return lines.join('\n') || '—';
}

// ---- embed builders ------------------------------------------------------------------------

async function buildPush(ev) {
  const branch = (ev.ref || '').replace('refs/heads/', '');
  const commits = ev.commits || [];
  const head = ev.head_commit || commits[commits.length - 1] || {};
  const sender = ev.sender || {};
  const pusher = ev.pusher || {};
  const color = COLOR.push;

  // Line stats + authoritative file list come from the compare API; fall back to the push payload.
  let stats = null, files = null;
  try {
    const cmp = dryRun ? ev._compare : await ghApi(`/repos/${owner}/${repoName}/compare/${ev.before}...${ev.after}`);
    if (cmp) { stats = cmp.stats; files = cmp.files; }
  } catch (e) { log('compare fetch failed: ' + e.message); }

  const f = collectFiles(commits, files);
  const author = { name: truncate(clean(sender.login || pusher.name || 'unknown'), 256), url: sender.html_url, icon_url: sender.avatar_url };
  const footer = { text: `${owner}/${repoName}`, icon_url: sender.avatar_url };
  const timestamp = head.timestamp || undefined;
  const net = stats ? stats.additions - stats.deletions : 0;
  const netStr = net > 0 ? `+${net}` : `${net}`; // negative sign comes for free; 0 -> "0"
  const changesField = stats
    ? field('Changes', `\u{1F4DD} ${fileCount(f)} files · \u{1F7E2} +${stats.additions} / \u{1F534} -${stats.deletions} · net ${netStr}`, true)
    : field('Files', `${fileCount(f)} changed`, true);

  // Batch push -> one collapsed embed, do not spam one per commit.
  if (commits.length > 1) {
    const shown = commits.slice(0, 10);
    const lines = shown.map(c => `• [\`${(c.id || '').slice(0, 7)}\`](${c.url}) ${truncate(clean((c.message || '').split('\n')[0]), 72)}`);
    const more = commits.length - shown.length;
    if (more > 0) lines.push(`…and **${more}** more`);
    return prune({
      author,
      title: truncate(`${commits.length} new commits on ${clean(branch)}`, LIM.title),
      description: truncate(lines.join('\n'), LIM.desc),
      url: ev.compare,
      color,
      thumbnail: { url: LOGO },
      fields: [field('Repository', `${owner}/${repoName}`, true), field('Branch', branch, true), changesField],
      footer, timestamp,
    });
  }

  // Single commit -> detailed embed.
  const msg = clean(head.message || '');
  const firstLine = msg.split('\n')[0];
  const body = msg.split('\n').slice(1).join('\n').trim();
  const sha = (head.id || '').slice(0, 7);
  const fields = [
    field('Repository', `${owner}/${repoName}`, true),
    field('Branch', branch, true),
    field('Commit', `[\`${sha}\`](${head.url})`, true),
    changesField,
  ];
  const commitAuthor = head.author?.username || head.author?.name;
  const pusherName = sender.login || pusher.name;
  if (commitAuthor && pusherName && commitAuthor !== pusherName) {
    fields.push(field('Authored by', `${commitAuthor} — pushed by ${pusherName}`, false));
  }
  fields.push(field(`Files changed (${fileCount(f)})`, fileListBlock(f), false));

  return prune({
    author,
    title: truncate(firstLine || '(no commit message)', LIM.title),
    description: body ? truncate(body, 800) : undefined,
    url: head.url,
    color,
    thumbnail: { url: LOGO },
    fields,
    footer, timestamp,
  });
}

async function buildStar(ev) {
  const sender = ev.sender || {};
  let stars = null;
  try {
    const repo = dryRun ? ev._repo : await ghApi(`/repos/${owner}/${repoName}`);
    stars = repo ? repo.stargazers_count : null;
  } catch (e) { log('repo fetch failed: ' + e.message); }

  const milestones = [10, 25, 50, 100, 250, 500, 1000, 2500, 5000];
  const milestone = stars != null && milestones.includes(stars) ? stars : null;
  const color = milestone ? COLOR.gold : COLOR.star;

  const fields = [];
  if (stars != null) fields.push(field('Total stars', `⭐ ${stars}`, true));
  if (milestone) fields.push(field('\u{1F389} Milestone', `Just crossed **${milestone}** stars!`, true));

  return prune({
    author: { name: truncate(clean(sender.login || 'someone'), 256), url: sender.html_url, icon_url: sender.avatar_url },
    title: '⭐ New star on boss-pvp',
    description: `**${clean(sender.login || 'someone')}** just starred the repo!`,
    url: `https://github.com/${owner}/${repoName}`,
    color,
    thumbnail: { url: LOGO },
    fields: fields.length ? fields : undefined,
    footer: { text: `${owner}/${repoName}` },
    timestamp: ev._now || new Date().toISOString(),
  });
}

// ---- delivery ------------------------------------------------------------------------------

async function send(payload) {
  if (!DISCORD_WEBHOOK) { log('DISCORD_WEBHOOK not set — skipping send'); return; }
  try {
    const res = await fetch(DISCORD_WEBHOOK, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      const t = await res.text().catch(() => '');
      log(`Discord webhook failed: ${res.status} ${t.slice(0, 300)}`);
    } else {
      log(`Discord webhook delivered (${res.status})`);
    }
  } catch (e) {
    log('Discord webhook error: ' + e.message);
  }
}

async function main() {
  const ev = readEvent();
  let embed;
  if (GITHUB_EVENT_NAME === 'push') embed = await buildPush(ev);
  else if (GITHUB_EVENT_NAME === 'watch') embed = await buildStar(ev);
  else { log('unsupported event: ' + GITHUB_EVENT_NAME); return; }

  const payload = { username: 'Boss PVP', avatar_url: LOGO, embeds: [embed] };
  if (dryRun) { console.log(JSON.stringify(payload, null, 2)); return; }
  await send(payload);
}

// Never crash the workflow over a notification.
main().catch(e => { log('fatal: ' + (e.stack || e.message)); process.exit(0); });
