import { useCallback, useState } from 'react';
import { toast } from 'sonner';

/**
 * Copies text to the clipboard and confirms it.
 *
 * Used for order numbers and correlation ids — the two strings a customer is most likely to be
 * asked to quote to support.
 */
export function useCopyToClipboard(resetAfterMs = 2000) {
  const [copied, setCopied] = useState(false);

  const copy = useCallback(
    async (text: string, label = 'Copied') => {
      try {
        await navigator.clipboard.writeText(text);
        setCopied(true);
        toast.success(label);
        setTimeout(() => setCopied(false), resetAfterMs);
      } catch {
        // Clipboard access is denied outside a secure context or without a user gesture.
        toast.error('Could not copy. Select the text and copy it manually.');
      }
    },
    [resetAfterMs],
  );

  return { copied, copy };
}
