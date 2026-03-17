const urlParams = new URLSearchParams(window.location.search);
const token = urlParams.get("token");
const player = urlParams.get("player");
const rawExpire = Number(urlParams.get("expire"));
const expire = Number.isFinite(rawExpire)
  ? (rawExpire < 1e12 ? rawExpire * 1000 : rawExpire)
  : NaN;

const dropZone = document.getElementById("dropZone");
const dropCta = document.getElementById("dropCta");
const fileInput = document.getElementById("fileInput");
const uploadBtn = document.getElementById("uploadBtn");
const songNameInput = document.getElementById("songName");
const statusHint = document.getElementById("statusHint");
const preview = document.getElementById("audioPreview");
const previewName = document.getElementById("previewName");
const previewMeta = document.getElementById("previewMeta");
const audioPlayer = document.getElementById("audioPlayer");
const tokenState = document.getElementById("tokenState");
const titlePlayer = document.getElementById("titlePlayer");
const expireLabel = document.getElementById("expire");
const rulesModal = document.getElementById("rulesModal");
const rulesCheckbox = document.getElementById("rulesCheckbox");
const confirmRules = document.getElementById("confirmRules");
const cancelRules = document.getElementById("cancelRules");

const RULES_COOLDOWN_MS = 10000;

let selectedFile = null;
let rulesReady = false;
let rulesFrame = null;

function setHint(message, tone = "") {
  if (!statusHint) {
    return;
  }
  statusHint.innerHTML = message;
  statusHint.className = tone ? `hint ${tone}` : "hint";
}

function formatSize(bytes) {
  if (!bytes) {
    return "0 KB";
  }

  const kb = bytes / 1024;
  if (kb < 1024) {
    return `${Math.round(kb)} KB`;
  }

  return `${(kb / 1024).toFixed(2)} MB`;
}

function setPreview(file) {
  if (!file) {
    preview.classList.remove("visible");
    dropCta.style.display = "block";
    previewName.textContent = "No hay archivo seleccionado";
    previewMeta.textContent = "Solo se permiten archivos MP3.";
    audioPlayer.removeAttribute("src");
    audioPlayer.load();
    return;
  }

  dropCta.style.display = "none";
  preview.classList.add("visible");
  previewName.textContent = file.name;
  previewMeta.textContent = `${formatSize(file.size)} - audio/mpeg`;
  audioPlayer.src = URL.createObjectURL(file);
}

function showExpired() {
  const forPlayer = player ? ` para <span class="name">${player}</span>` : "";
  document.body.innerHTML = `
    <div class="closed-card">
      <img src="/assets/nomelacontes.png" alt="Enlace expirado" class="closed-icon">
      <h2 class="error-title">Este enlace${forPlayer} ha expirado.<br>Genera uno nuevo dentro del juego.</h2>
    </div>
  `;
}

function showError(details) {
  const forPlayer = player ? ` para <span class="name">${player}</span>` : "";
  const detailBlock = details ? `<p class="error-detail">${details}</p>` : "";
  document.body.innerHTML = `
    <div class="closed-card">
      <img src="/assets/nomelacontes.png" alt="Error de subida" class="closed-icon">
      <h2 class="error-title">Hubo un error al subir${forPlayer}.<br>Crea un enlace nuevo dentro del juego.</h2>
      ${detailBlock}
    </div>
  `;
}

function showSuccess(message) {
  const forPlayer = player ? ` para <span class="name">${player}</span>` : "";
  document.body.innerHTML = `
    <div class="closed-card">
      <img src="/assets/success.png" alt="Subida exitosa" class="closed-icon">
      <h1 class="success-title">Subida completada${forPlayer}!<br>Continua dentro del juego.</h1>
      ${message ? `<p class="error-detail">${message}</p>` : ""}
    </div>
  `;
}

function updateTokenState() {
  if (!tokenState) {
    return;
  }

  if (token) {
    tokenState.textContent = "Token listo";
    tokenState.classList.add("ready");
  } else {
    tokenState.textContent = "Falta el token";
  }
}

function updateExpiryLabel() {
  if (!expireLabel) {
    return;
  }

  if (!expire || Number.isNaN(expire)) {
    expireLabel.textContent = "Este es un enlace de subida de un solo uso.";
    return;
  }

  const render = () => {
    const diff = Math.floor((expire - Date.now()) / 1000);
    if (diff <= 0) {
      expireLabel.textContent = "Este enlace expira en 00s";
      return false;
    }

    const minutes = Math.floor(diff / 60);
    const seconds = String(diff % 60).padStart(2, "0");
    expireLabel.textContent = minutes > 0
      ? `Este enlace expira en ${minutes}m ${seconds}s`
      : `Este enlace expira en ${seconds}s`;
    return true;
  };

  if (!render()) {
    showExpired();
    return;
  }

  const taskId = setInterval(() => {
    if (!render()) {
      clearInterval(taskId);
      showExpired();
    }
  }, 500);
}

function validateSelectedFile(file) {
  return file && file.name.toLowerCase().endsWith(".mp3") && (file.type === "" || file.type === "audio/mpeg" || file.type === "audio/mp3");
}

function handleFileSelect(file) {
  if (!validateSelectedFile(file)) {
    selectedFile = null;
    uploadBtn.disabled = true;
    setPreview(null);
    setHint("Solo se permiten archivos MP3.", "error");
    return;
  }

  selectedFile = file;
  uploadBtn.disabled = false;
  setPreview(file);
  setHint('Revisa las reglas y manten el navegador abierto mientras se completa la subida.<br><span class="warn">Cualquier subida que rompa las reglas del servidor puede resultar en un ban permanente.</span>');
}

function updateConfirmState() {
  confirmRules.disabled = !(rulesCheckbox.checked && rulesReady);
}

function setButtonProgress(value) {
  confirmRules.style.setProperty("--progress", value);
}

function stopRulesCountdown() {
  if (rulesFrame) {
    cancelAnimationFrame(rulesFrame);
    rulesFrame = null;
  }
  rulesReady = false;
  setButtonProgress(0);
}

function startRulesCountdown() {
  stopRulesCountdown();
  const start = Date.now();
  confirmRules.classList.add("timed-progress");

  const tick = () => {
    const progress = Math.min((Date.now() - start) / RULES_COOLDOWN_MS, 1);
    setButtonProgress(progress);

    if (progress >= 1) {
      rulesReady = true;
      updateConfirmState();
      rulesFrame = null;
      return;
    }

    rulesFrame = requestAnimationFrame(tick);
  };

  rulesFrame = requestAnimationFrame(tick);
}

function openRulesModal() {
  if (!token) {
    setHint("A esta pagina le falta un token de subida valido. Genera un enlace nuevo dentro del juego.", "error");
    return;
  }

  if (!selectedFile) {
    setHint("Selecciona un archivo MP3 antes de subirlo.", "error");
    return;
  }

  if (!songNameInput.value.trim()) {
    setHint("Escribe el nombre del disco antes de subirlo.", "error");
    songNameInput.focus();
    return;
  }

  rulesCheckbox.checked = false;
  rulesReady = false;
  confirmRules.disabled = true;
  startRulesCountdown();
  rulesModal.classList.add("open");
  rulesModal.setAttribute("aria-hidden", "false");
}

function closeRulesModal() {
  stopRulesCountdown();
  rulesModal.classList.remove("open");
  rulesModal.setAttribute("aria-hidden", "true");
}

async function performUpload() {
  const songName = songNameInput.value.trim();

  try {
    const response = await fetch(`/api/upload?songName=${encodeURIComponent(songName)}&filename=${encodeURIComponent(selectedFile.name)}`, {
      method: "POST",
      headers: {
        "Content-Type": "audio/mpeg",
        "X-CustomDiscs-Token": token
      },
      body: selectedFile
    });

    const contentType = response.headers.get("content-type") || "";
    const isJson = contentType.includes("application/json");
    const payload = isJson ? await response.json() : await response.text();

    if (!response.ok) {
      const detail = isJson && payload.message ? payload.message : `La subida fallo (${response.status}).`;
      showError(detail);
      return;
    }

    if (!isJson || !payload.success) {
      showError("Respuesta inesperada del servidor.");
      return;
    }

    showSuccess(payload.message || "El disco de tu mano principal fue actualizado.");
  } catch (error) {
    showError(error.message || "Error inesperado.");
  }
}

if (player && titlePlayer) {
  titlePlayer.innerHTML = ` para <span class="name">${player}</span>`;
}

updateTokenState();
updateExpiryLabel();

if (dropZone && fileInput) {
  dropZone.addEventListener("click", () => fileInput.click());

  fileInput.addEventListener("change", () => {
    handleFileSelect(fileInput.files[0] || null);
  });

  dropZone.addEventListener("dragover", (event) => {
    event.preventDefault();
    dropZone.classList.add("highlight");
  });

  dropZone.addEventListener("dragleave", () => {
    dropZone.classList.remove("highlight");
  });

  dropZone.addEventListener("drop", (event) => {
    event.preventDefault();
    dropZone.classList.remove("highlight");
    handleFileSelect(event.dataTransfer.files[0] || null);
  });
}

if (uploadBtn) {
  uploadBtn.addEventListener("click", openRulesModal);
}

if (rulesCheckbox) {
  rulesCheckbox.addEventListener("change", updateConfirmState);
}

if (cancelRules) {
  cancelRules.addEventListener("click", closeRulesModal);
}

if (confirmRules) {
  confirmRules.addEventListener("click", () => {
    confirmRules.disabled = true;
    closeRulesModal();
    performUpload();
  });
}
