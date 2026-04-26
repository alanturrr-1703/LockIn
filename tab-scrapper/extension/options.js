const DEFAULT_PROFILE = {
  id: "default-profile",
  name: "Computer science student",
  description:
    "Focus on algorithms, data structures, system design, ML, and practical coding content.",
  tags: [
    "dsa",
    "algorithms",
    "data structures",
    "ml",
    "dl",
    "system design",
    "python",
  ],
  active: true,
};

const $ = (id) => document.getElementById(id);

const state = {
  profiles: [],
  activeProfileId: "",
};

function uniqStrings(list) {
  return [
    ...new Set(
      (list || []).map((value) => String(value || "").trim()).filter(Boolean),
    ),
  ];
}

function normalizeTag(tag) {
  return String(tag || "")
    .trim()
    .toLowerCase()
    .replace(/\s+/g, " ");
}

function splitTags(text) {
  return uniqStrings(
    String(text || "")
      .split(/[,\n]/g)
      .map(normalizeTag),
  );
}

function makeId(prefix) {
  if (crypto && typeof crypto.randomUUID === "function") {
    return `${prefix}-${crypto.randomUUID()}`;
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function ensureProfile(profile) {
  return {
    id: String(profile && profile.id ? profile.id : makeId("profile")),
    name:
      String(
        profile && profile.name ? profile.name : DEFAULT_PROFILE.name,
      ).trim() || DEFAULT_PROFILE.name,
    description: String(
      profile && profile.description ? profile.description : "",
    ).trim(),
    tags: uniqStrings(
      Array.isArray(profile && profile.tags)
        ? profile.tags.map(normalizeTag)
        : [],
    ),
    active: !!(profile && profile.active),
  };
}

function defaultProfile() {
  return ensureProfile(DEFAULT_PROFILE);
}

function getActiveProfile() {
  return (
    state.profiles.find((p) => p.id === state.activeProfileId) ||
    state.profiles[0] ||
    defaultProfile()
  );
}

async function loadState() {
  const stored = await chrome.storage.local.get([
    "profiles",
    "activeProfileId",
    "topic",
  ]);
  state.profiles =
    Array.isArray(stored.profiles) && stored.profiles.length
      ? stored.profiles.map(ensureProfile)
      : [defaultProfile()];
  state.activeProfileId = String(
    stored.activeProfileId ||
      state.profiles.find((p) => p.active)?.id ||
      state.profiles[0].id,
  );
  if (stored.topic && !state.profiles.some((p) => p.description)) {
    state.profiles[0].description = String(stored.topic).trim();
  }
}

function renderProfileSelect() {
  const select = $("profile-id");
  select.innerHTML = "";
  for (const p of state.profiles) {
    const opt = document.createElement("option");
    opt.value = p.id;
    opt.textContent = `${p.active || p.id === state.activeProfileId ? "* " : ""}${p.name}`;
    if (p.id === state.activeProfileId) opt.selected = true;
    select.appendChild(opt);
  }
}

function renderProfiles() {
  const list = $("profile-list");
  list.innerHTML = "";
  for (const p of state.profiles) {
    const card = document.createElement("article");
    card.className = `profile-card${p.id === state.activeProfileId ? " active" : ""}`;

    const header = document.createElement("header");
    const left = document.createElement("div");
    const title = document.createElement("h3");
    title.textContent = p.name;
    const desc = document.createElement("div");
    desc.className = "muted";
    desc.textContent = p.description || "No description yet.";
    left.appendChild(title);
    left.appendChild(desc);
    const right = document.createElement("div");
    right.className = "muted";
    right.textContent = p.id === state.activeProfileId ? "Active" : "";
    header.appendChild(left);
    header.appendChild(right);
    card.appendChild(header);

    const tags = document.createElement("div");
    tags.className = "tag-row";
    for (const tag of p.tags) {
      const chip = document.createElement("span");
      chip.className = "tag";
      const label = document.createElement("span");
      label.textContent = tag;
      chip.appendChild(label);
      tags.appendChild(chip);
    }
    card.appendChild(tags);

    card.addEventListener("click", () => {
      state.activeProfileId = p.id;
      render();
      setProfileStatus(`Selected ${p.name}.`);
    });
    list.appendChild(card);
  }
}

function renderTagRow() {
  const row = $("tag-row");
  row.innerHTML = "";
  const profile = getActiveProfile();
  for (const tag of profile.tags) {
    const chip = document.createElement("span");
    chip.className = "tag";
    const label = document.createElement("span");
    label.textContent = tag;
    chip.appendChild(label);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = "×";
    remove.addEventListener("click", async (e) => {
      e.stopPropagation();
      profile.tags = profile.tags.filter((t) => t !== tag);
      await persistProfiles();
    });
    chip.appendChild(remove);
    row.appendChild(chip);
  }
}

function renderFields() {
  const profile = getActiveProfile();
  $("profile-name").value = profile.name;
  $("profile-description").value = profile.description;
  $("profile-tags").value = "";
  $("profile-status").textContent =
    `${state.profiles.length} profile(s) configured. Active: ${profile.name}.`;
}

function render() {
  renderProfileSelect();
  renderProfiles();
  renderFields();
  renderTagRow();
}

function setProfileStatus(msg) {
  $("profile-status").textContent = msg;
}

async function persistProfiles() {
  const profile = getActiveProfile();
  const nameVal = String($("profile-name").value || "").trim();
  if (nameVal) profile.name = nameVal;
  const descVal = String($("profile-description").value || "").trim();
  if (descVal !== undefined) profile.description = descVal;
  await chrome.storage.local.set({
    profiles: state.profiles,
    activeProfileId: state.activeProfileId,
  });
  render();
}

async function createProfile() {
  const name = String($("profile-name").value || "").trim();
  const description = String($("profile-description").value || "").trim();
  const tags = splitTags($("profile-tags").value);
  const profile = ensureProfile({
    id: makeId("profile"),
    name: name || DEFAULT_PROFILE.name,
    description,
    tags,
    active: false,
  });
  state.profiles.push(profile);
  state.activeProfileId = profile.id;
  await chrome.storage.local.set({
    profiles: state.profiles,
    activeProfileId: state.activeProfileId,
  });
  setProfileStatus(`Created ${profile.name}.`);
  render();
}

async function deleteProfile() {
  if (state.profiles.length <= 1) {
    setProfileStatus("Keep at least one profile.");
    return;
  }
  const profile = getActiveProfile();
  state.profiles = state.profiles.filter((p) => p.id !== profile.id);
  state.activeProfileId = state.profiles[0].id;
  await chrome.storage.local.set({
    profiles: state.profiles,
    activeProfileId: state.activeProfileId,
  });
  setProfileStatus(`Deleted ${profile.name}.`);
  render();
}

async function setActiveProfile() {
  const profile =
    state.profiles.find((p) => p.id === $("profile-id").value) ||
    getActiveProfile();
  state.activeProfileId = profile.id;
  await chrome.storage.local.set({
    profiles: state.profiles,
    activeProfileId: state.activeProfileId,
  });
  setProfileStatus(`${profile.name} is now active.`);
  render();
}

async function addTag() {
  const profile = getActiveProfile();
  const tags = splitTags($("tag-input").value);
  if (tags.length === 0) return;
  profile.tags = uniqStrings([...(profile.tags || []), ...tags]);
  $("tag-input").value = "";
  await persistProfiles();
}

async function suggestTagsFromModel() {
  const profile = getActiveProfile();
  setProfileStatus("Asking Ollama to suggest tags…");
  const response = await chrome.runtime.sendMessage({
    cmd: "suggest-tags",
    profile,
    topic: profile.description || profile.name,
  });
  if (!response || !response.ok) {
    setProfileStatus(
      (response && response.reason) ||
        "Tag suggestion failed — is Ollama running?",
    );
    return;
  }
  profile.tags = uniqStrings([
    ...(profile.tags || []),
    ...(response.tags || []),
  ]);
  await persistProfiles();
  setProfileStatus(`Added ${(response.tags || []).length} suggested tag(s).`);
}

document.addEventListener("DOMContentLoaded", async () => {
  await loadState();
  render();

  $("profile-name").addEventListener("change", persistProfiles);
  $("profile-description").addEventListener("change", persistProfiles);
  $("profile-id").addEventListener("change", async (e) => {
    state.activeProfileId = String(e.target.value || state.activeProfileId);
    await chrome.storage.local.set({ activeProfileId: state.activeProfileId });
    render();
  });

  $("create-profile").addEventListener("click", createProfile);
  $("delete-profile").addEventListener("click", deleteProfile);
  $("set-active").addEventListener("click", setActiveProfile);
  $("add-tag").addEventListener("click", addTag);
  $("suggest-tags").addEventListener("click", suggestTagsFromModel);
  $("save-all").addEventListener("click", async () => {
    await persistProfiles();
    setProfileStatus(`Saved — active profile: ${getActiveProfile().name}.`);
  });

  $("tag-input").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      addTag();
    }
  });

  $("open-popup").addEventListener("click", () => {
    chrome.tabs.getCurrent((tab) => {
      if (tab && tab.id) {
        chrome.tabs.remove(tab.id);
      }
    });
  });
});
