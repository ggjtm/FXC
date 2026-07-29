/*
 * The mouseover dropdown of controls required by docs/DESIGN.md §6.4.
 * Served from the fxc-common jar at /common/fxc-menu.js.
 *
 * Opening is pure CSS (:hover / :focus-within in fxc.css) so it works without JS;
 * this file adds the operator-safety behaviour on top:
 *   - destructive items (data-danger) need a second click to confirm, because
 *     "clear the order book" mass-cancels live orders;
 *   - every item disables itself while its request is in flight, so an impatient
 *     double-click cannot fire twice;
 *   - Escape dismisses a menu the pointer is still resting on.
 *
 * Markup contract:
 *   <div class="fxc-menu" id="controls">
 *     <button class="fxc-menu-btn" type="button" aria-haspopup="menu">Controls</button>
 *     <div class="fxc-menu-panel" role="menu">
 *       <div class="fxc-menu-label">Trading session</div>
 *       <button class="fxc-menu-item" role="menuitem" data-action="halt"
 *               data-danger data-confirm-label="Click again to halt">Halt trading</button>
 *     </div>
 *   </div>
 */
(function (global) {
  "use strict";

  var Fxc = global.Fxc || (global.Fxc = {});
  var CONFIRM_TIMEOUT_MS = 3000;

  /**
   * Wire a menu's items to handlers.
   *
   * @param root     the .fxc-menu element
   * @param handlers { actionName: function(item) -> Promise|undefined }
   * @param onError  optional failure callback (defaults to the shared banner)
   */
  Fxc.menu = function (root, handlers, onError) {
    if (!root) {
      return null;
    }
    var items = Array.prototype.slice.call(root.querySelectorAll(".fxc-menu-item"));
    var confirming = null;
    var confirmTimer = null;
    var busy = false;

    function clearConfirm() {
      if (confirmTimer) {
        global.clearTimeout(confirmTimer);
        confirmTimer = null;
      }
      if (confirming) {
        confirming.classList.remove("is-confirming");
        confirming.textContent = confirming.getAttribute("data-label");
        confirming = null;
      }
    }

    function setBusy(item, on) {
      busy = on;
      items.forEach(function (other) {
        other.disabled = on;
      });
      if (on) {
        item.textContent = item.getAttribute("data-label") + " …";
      } else {
        item.textContent = item.getAttribute("data-label");
      }
    }

    function dismiss() {
      root.classList.add("is-dismissed");
      var btn = root.querySelector(".fxc-menu-btn");
      if (btn) {
        btn.blur();
      }
      if (document.activeElement && root.contains(document.activeElement)) {
        document.activeElement.blur();
      }
    }

    items.forEach(function (item) {
      // Remember the resting label so confirm/busy states can restore it.
      item.setAttribute("data-label", item.textContent.trim());

      item.addEventListener("click", function () {
        if (busy) {
          return;
        }
        var action = item.getAttribute("data-action");
        var handler = handlers[action];
        if (!handler) {
          return;
        }

        if (item.hasAttribute("data-danger") && confirming !== item) {
          clearConfirm();
          confirming = item;
          item.classList.add("is-confirming");
          item.textContent = item.getAttribute("data-confirm-label") || "Click again to confirm";
          confirmTimer = global.setTimeout(clearConfirm, CONFIRM_TIMEOUT_MS);
          return;
        }

        clearConfirm();
        setBusy(item, true);
        var settle = function (err) {
          setBusy(item, false);
          if (err) {
            if (onError) {
              onError(err);
            } else if (Fxc.banner) {
              Fxc.banner(String(err.message || err));
            }
          } else {
            dismiss();
          }
        };
        var result;
        try {
          result = handler(item);
        } catch (e) {
          settle(e);
          return;
        }
        if (result && typeof result.then === "function") {
          result.then(function () { settle(null); }, settle);
        } else {
          settle(null);
        }
      });
    });

    // Leaving the menu resets both the dismissal and any pending confirm, so the
    // next hover starts from a clean state rather than a half-armed one.
    root.addEventListener("mouseleave", function () {
      root.classList.remove("is-dismissed");
      if (!busy) {
        clearConfirm();
      }
    });

    root.addEventListener("keydown", function (ev) {
      if (ev.key === "Escape") {
        clearConfirm();
        dismiss();
      }
    });

    return {
      /** Enable/disable a single action (e.g. grey out Resume while already open). */
      setEnabled: function (action, enabled) {
        items.forEach(function (item) {
          if (item.getAttribute("data-action") === action && !busy) {
            item.disabled = !enabled;
          }
        });
      }
    };
  };
}(window));
