import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * 合并 Tailwind class 的工具函数（shadcn 标准实现）
 *
 * clsx 负责处理条件类名（对象/数组/假值过滤），
 * twMerge 负责解决 Tailwind 类冲突（后写的同类属性覆盖先写的，
 * 例如 cn('px-2', 'px-4') => 'px-4'，而不是两个都留着）。
 *
 * @param {...any} inputs class 片段，支持字符串/数组/对象
 * @returns {string} 合并去冲突后的 class 字符串
 */
export function cn(...inputs) {
  return twMerge(clsx(inputs))
}
