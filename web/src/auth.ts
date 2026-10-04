import { pb } from "./api";
import { errorMessage, h, openModal, toast } from "./ui";

export function openAuth(onDone: () => void) {
  let mode: "login" | "register" = "login";
  // autocomplete off: Brave 1.96's autofill-on-typing popup for "name" fields
  // crashes the whole browser (crash key autofill-suggestions-shown-on-typing)
  const name = h("input", { type: "text", name: "display_name", placeholder: "Display name", autocomplete: "off" });
  const email = h("input", { type: "email", placeholder: "Email", autocomplete: "email", required: true });
  const password = h("input", { type: "password", placeholder: "Password (min. 8)", autocomplete: "current-password", required: true });
  const nameRow = h("label", { hidden: true }, "Name", name);
  const err = h("p", { class: "error" });
  const submit = h("button", { class: "glow", type: "submit" }, "Sign in");
  const toggle = h("button", { type: "button", class: "link" }, "Create an account");

  const form = h(
    "form",
    { class: "stack" },
    nameRow,
    h("label", {}, "Email", email),
    h("label", {}, "Password", password),
    err,
    h("div", { class: "button-row-2" }, toggle, submit),
  );

  toggle.addEventListener("click", () => {
    mode = mode === "login" ? "register" : "login";
    nameRow.hidden = mode === "login";
    submit.textContent = mode === "login" ? "Sign in" : "Register";
    toggle.textContent = mode === "login" ? "Create an account" : "I have an account";
    password.autocomplete = mode === "login" ? "current-password" : "new-password";
  });

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    err.textContent = "";
    submit.disabled = true;
    try {
      const users = pb.collection("users");
      if (mode === "register") {
        await users.create({
          email: email.value,
          password: password.value,
          passwordConfirm: password.value,
          name: name.value,
        });
      }
      await users.authWithPassword(email.value, password.value);
      close();
      toast(`Signed in as ${pb.authStore.record?.name || email.value}`);
      onDone();
    } catch (ex) {
      err.textContent = errorMessage(ex);
    } finally {
      submit.disabled = false;
    }
  });

  const close = openModal("Account", form);
  email.focus();
}

export function openAccount(onDone: () => void) {
  const u = pb.authStore.record!;
  const close = openModal(
    "Account",
    h("div", { class: "stack" }, h("p", {}, u.name || "(no name)"), h("p", { class: "dim" }, u.email)),
    h(
      "button",
      {
        onclick: () => {
          pb.authStore.clear();
          close();
          onDone();
        },
      },
      "Sign out",
    ),
  );
}
