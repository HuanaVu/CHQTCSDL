const refs = {
  openLoginButtons: document.querySelectorAll("#openLoginButton, #heroLoginButton, #openLoginButtonSecondary"),
  authFrame: document.getElementById("authFrame"),
  switchModeButton: document.getElementById("switchModeButton"),
  coverTitle: document.getElementById("coverTitle"),
  coverBrand: document.getElementById("coverBrand"),
  coverDescLine1: document.getElementById("coverDescLine1"),
  coverDescLine2: document.getElementById("coverDescLine2"),
  loginForm: document.getElementById("loginForm"),
  registerForm: document.getElementById("registerForm"),
  resultBox: document.getElementById("resultBox"),
  healthDot: document.getElementById("healthDot"),
  healthText: document.getElementById("healthText"),
  dbText: document.getElementById("dbText")
};

let registerMode = false;

function normalizeText(value) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .trim()
    .toLowerCase();
}

function routeForRole(role) {
  const normalizedRole = normalizeText(role);
  if (normalizedRole.includes("nhan vien kho") || normalizedRole.includes("kho")) {
    return "/pages/warehouse-management.html";
  }
  if (normalizedRole.includes("nhan vien")) {
    return "/pages/staff-workspace.html";
  }
  if (normalizedRole.includes("quan ly")) {
    return "/pages/admin-management.html";
  }
  return "/pages/customer-portal.html";
}

function storeAuthContext(user) {
  const payload = {
    email: String(user?.email || ""),
    userId: String(user?.id || ""),
    role: String(user?.role || "")
  };
  try {
    sessionStorage.setItem("rtd.auth", JSON.stringify(payload));
  } catch (_) {
    // Keep login flow working even if browser storage is unavailable.
  }
}

function setMode(isRegisterMode) {
  registerMode = isRegisterMode;
  refs.authFrame.classList.toggle("register-mode", registerMode);

  if (registerMode) {
    refs.switchModeButton.textContent = "Đăng ký >>";
    refs.coverTitle.textContent = "Chào mừng trở lại";
    refs.coverBrand.textContent = "Royal TheDreamers";
    refs.coverDescLine1.textContent = "Bạn đã có tài khoản rồi phải không?";
    refs.coverDescLine2.textContent = "Vui lòng đăng nhập để tiếp tục sử dụng dịch vụ";
  } else {
    refs.switchModeButton.textContent = "<< Đăng nhập";
    refs.coverTitle.textContent = "Chào mừng bạn đến với nhà hàng";
    refs.coverBrand.textContent = "Royal TheDreamers";
    refs.coverDescLine1.textContent = "Để sử dụng dịch vụ tại đây vui lòng";
    refs.coverDescLine2.textContent = "đăng nhập với tài khoản cá nhân của bạn";
  }
}

function openAuthSection(showRegisterMode = false) {
  document.body.classList.add("auth-open");
  if (refs.authFrame) {
    refs.authFrame.scrollIntoView({ behavior: "smooth", block: "start" });
  }
  setMode(showRegisterMode);
  if (!showRegisterMode) {
    window.setTimeout(() => {
      if (refs.loginForm) {
        const firstField = refs.loginForm.querySelector("input");
        if (firstField) {
          firstField.focus();
        }
      }
    }, 250);
  }
}

function showResult(message, data) {
  if (!refs.resultBox) {
    return;
  }
  if (typeof data === "undefined") {
    refs.resultBox.textContent = String(message || "");
    return;
  }
  refs.resultBox.textContent = `${message}\n${JSON.stringify(data, null, 2)}`;
}

async function requestJson(path, options) {
  const response = await fetch(path, options);
  const text = await response.text();
  const payload = text ? JSON.parse(text) : {};
  if (!response.ok) {
    throw new Error(payload.message || `HTTP ${response.status}`);
  }
  return payload;
}

function bindPasswordToggle() {
  document.querySelectorAll(".toggle-eye").forEach((btn) => {
    btn.addEventListener("click", () => {
      const targetId = btn.getAttribute("data-target");
      const input = document.getElementById(targetId);
      if (!input) {
        return;
      }
      input.type = input.type === "password" ? "text" : "password";
    });
  });
}

async function refreshHealth() {
  if (!refs.healthDot || !refs.healthText || !refs.dbText) {
    return;
  }
  try {
    const result = await requestJson("/api/health");
    const isOk = result.status === "UP";
    refs.healthDot.classList.remove("ok", "error");
    refs.healthDot.classList.add(isOk ? "ok" : "error");
    refs.healthText.textContent = isOk ? "API đang hoạt động" : "API gặp sự cố";
    refs.dbText.textContent = `DB: ${result.database || "--"}`;
  } catch (error) {
    refs.healthDot.classList.remove("ok");
    refs.healthDot.classList.add("error");
    refs.healthText.textContent = "Không kết nối được API";
    refs.dbText.textContent = "DB: --";
  }
}

async function onLoginSubmit(event) {
  event.preventDefault();
  const formData = new FormData(refs.loginForm);
  const payload = {
    email: String(formData.get("email") || "").trim(),
    password: String(formData.get("password") || "")
  };

  if (!payload.email || !payload.password) {
    showResult("Vui lòng nhập email và mật khẩu.");
    return;
  }

  showResult("Đang xử lý đăng nhập...");
  try {
    const result = await requestJson("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    showResult("Đăng nhập thành công.", result);

    const nextPage = routeForRole(result?.user?.role);
    storeAuthContext(result?.user || {});
    setTimeout(() => {
      window.location.href = nextPage;
    }, 450);
  } catch (error) {
    showResult(`Đăng nhập thất bại: ${error.message}`);
  }
}

async function onRegisterSubmit(event) {
  event.preventDefault();
  const formData = new FormData(refs.registerForm);
  const payload = {
    name: String(formData.get("name") || "").trim(),
    email: String(formData.get("email") || "").trim(),
    password: String(formData.get("password") || "")
  };

  if (!payload.name || !payload.email || !payload.password) {
    showResult("Vui lòng nhập đầy đủ tên, email và mật khẩu.");
    return;
  }

  showResult("Đang xử lý đăng ký...");
  try {
    const result = await requestJson("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    showResult("Đăng ký thành công. Bạn có thể đăng nhập ngay.", result);
    refs.registerForm.reset();
    setMode(false);
  } catch (error) {
    showResult(`Đăng ký thất bại: ${error.message}`);
  }
}

function bindEvents() {
  refs.switchModeButton.addEventListener("click", () => setMode(!registerMode));
  refs.loginForm.addEventListener("submit", onLoginSubmit);
  refs.registerForm.addEventListener("submit", onRegisterSubmit);
  refs.openLoginButtons.forEach((button) => {
    button.addEventListener("click", () => openAuthSection(false));
  });
  bindPasswordToggle();
}

bindEvents();
setMode(false);
refreshHealth();
