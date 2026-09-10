import { onBeforeUnmount, type Ref } from 'vue';

/**
 * Shared native <dialog> chrome: modal open with scroll lock, backdrop-click close
 * and safe teardown. Components keep their own open triggers and content.
 */
export function useModalDialog(dialog: Readonly<Ref<HTMLDialogElement | null>>, onClose?: () => void) {
  let savedOverflow: { value: string; priority: string } | null = null;
  let disposed = false;

  function lockScroll(): void {
    const style = document.documentElement.style;
    savedOverflow ??= { value: style.getPropertyValue('overflow'), priority: style.getPropertyPriority('overflow') };
    style.setProperty('overflow', 'hidden');
  }

  function restoreScroll(): void {
    if (!savedOverflow) return;
    const style = document.documentElement.style;
    if (savedOverflow.value) style.setProperty('overflow', savedOverflow.value, savedOverflow.priority);
    else style.removeProperty('overflow');
    savedOverflow = null;
  }

  /** Opens the modal and locks scrolling; returns false if it is disposed, detached or already open. */
  function showModal(): boolean {
    const element = dialog.value;
    if (disposed || !element || !element.isConnected || element.open) return false;
    element.showModal();
    lockScroll();
    return true;
  }

  function close(): void {
    dialog.value?.close();
  }

  /** Bind to the dialog's native @close event. */
  function handleClose(): void {
    if (dialog.value?.open) return;
    restoreScroll();
    onClose?.();
  }

  /** A click lands on the backdrop when its target is the dialog but the point is outside its box. */
  function closeOnBackdrop(event: MouseEvent): void {
    const element = dialog.value;
    if (!element || event.target !== element) return;
    const bounds = element.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right ||
        event.clientY < bounds.top || event.clientY > bounds.bottom) {
      element.close();
    }
  }

  onBeforeUnmount(() => {
    disposed = true;
    dialog.value?.close();
    restoreScroll();
  });

  return { showModal, close, handleClose, closeOnBackdrop, isDisposed: (): boolean => disposed };
}
