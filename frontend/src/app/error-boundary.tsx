import { AlertTriangle } from 'lucide-react';
import { Component, type ErrorInfo, type ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import { config } from '@/shared/config';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Catches render-time crashes.
 *
 * TanStack Query handles *data* failures; this is the last line for the ones it cannot — a null
 * dereference in a component, a bad cast, a third-party widget throwing. Without it, one broken
 * component blanks the entire page.
 *
 * Still a class component: React has no hook equivalent for `componentDidCatch`.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Where a real error reporter (Sentry, etc.) would be called. Logging keeps the stack
    // reachable in the meantime instead of swallowing it.
    console.error('Unhandled render error', error, info.componentStack);
  }

  private reset = (): void => {
    this.setState({ error: null });
  };

  render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;
    if (this.props.fallback) return this.props.fallback;

    return (
      <div className="flex min-h-[60vh] flex-col items-center justify-center px-6 text-center">
        <AlertTriangle className="mb-4 h-10 w-10 text-destructive" aria-hidden />
        <h1 className="text-xl font-semibold">Something broke on this page</h1>
        <p className="mt-2 max-w-md text-sm text-muted-foreground">
          The rest of the app is fine. Try again, or go back to the homepage.
        </p>

        {config.isDev ? (
          <pre className="mt-6 max-w-2xl overflow-x-auto rounded-md bg-muted p-4 text-left text-xs">
            {error.message}
            {'\n'}
            {error.stack}
          </pre>
        ) : null}

        <div className="mt-6 flex gap-2">
          <Button onClick={this.reset}>Try again</Button>
          <Button variant="outline" onClick={() => window.location.assign('/')}>
            Go home
          </Button>
        </div>
      </div>
    );
  }
}
